package com.chaosforge.medins.service;

import com.chaosforge.medins.adapter.ChangeOrderExporter;
import com.chaosforge.medins.adapter.GuardResult;
import com.chaosforge.medins.adapter.IfaceRegistryClient;
import com.chaosforge.medins.adapter.IfaceRegistryException;
import com.chaosforge.medins.adapter.WriteBackGuard;
import com.chaosforge.medins.adapter.WriteBackRequest;
import com.chaosforge.medins.adapter.WriteResponse;
import com.chaosforge.medins.attribution.AttributionRequest;
import com.chaosforge.medins.attribution.AttributionResult;
import com.chaosforge.medins.attribution.AttributionService;
import com.chaosforge.medins.attribution.ModelGateway;
import com.chaosforge.medins.catalog.ListFieldDictionary;
import com.chaosforge.medins.config.SecretRedactor;
import com.chaosforge.medins.lineage.DownstreamReachability;
import com.chaosforge.medins.lineage.FieldLineageGraph;
import com.chaosforge.medins.lineage.GraphBuilder;
import com.chaosforge.medins.lineage.ImpactCalculator;
import com.chaosforge.medins.lineage.ReachabilityResult;
import com.chaosforge.medins.model.ChangeKind;
import com.chaosforge.medins.model.FieldKey;
import com.chaosforge.medins.model.ImpactRecord;
import com.chaosforge.medins.model.ImpactScope;
import com.chaosforge.medins.model.ImpactStatus;
import com.chaosforge.medins.model.ImpactType;
import com.chaosforge.medins.model.ListField;
import com.chaosforge.medins.model.Verdict;
import com.chaosforge.medins.sqlparse.ManualReviewItem;
import com.chaosforge.medins.sqlparse.ParseReport;
import com.chaosforge.medins.sqlparse.ParseResult;
import com.chaosforge.medins.sqlparse.SqlDigest;
import com.chaosforge.medins.sqlparse.SqlViewParser;
import com.chaosforge.medins.sqlparse.StructuredError;
import com.chaosforge.medins.store.AuditLog;
import com.chaosforge.medins.store.FailedEventLog;
import com.chaosforge.medins.store.ImpactStore;
import com.chaosforge.medins.store.RunStats;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 影响面分析主流程编排：读取视图定义 → SQL 解析 → 图构建 → 下游可达 → 清单对齐 →
 * 归因 → 待确认 → 人工确认 → 幂等回写（九步，见 {@link AnalysisResult#STEPS}）。
 *
 * <p>各步失败映射到明确的降级分支，绝不整轮中断：
 * <ul>
 *   <li>库不可达 → 沿用上次已存图并标注「图已过期」，不推算新结论；</li>
 *   <li>字典不可用 → 清单字段区空并报错，<b>不按空字典算影响面</b>；</li>
 *   <li>模型不可达 → DEGRADED 且禁止回写；</li>
 *   <li>解析失败 → 按行列号报告并给待人工核对清单。</li>
 * </ul>
 *
 * <p>外部边界以可注入的函数式接口提供（ViewSource / GraphSource / DictionarySource），
 * 默认实现分别走「输入 SQL」「{@link GraphBuilder}」「{@link ListFieldDictionary#fromCsv}」，
 * 从而让主流程真实可跑、单测又可确定性替身。<b>本类不 import 任何 Swing / AWT 类</b>。
 */
public final class ImpactAnalysisService {

    // ---- 可注入外部边界（默认实现见字段初始化） ----

    /** 读取视图定义 SQL；抛异常表示「库不可达」。 */
    @FunctionalInterface
    public interface ViewSource {
        String read(String viewName) throws Exception;
    }

    /** 由解析结果构图；默认走 GraphBuilder.build(List&lt;ParseResult&gt;)。 */
    @FunctionalInterface
    public interface GraphSource {
        FieldLineageGraph build(List<ParseResult> parsed);
    }

    /** 加载清单字段字典的 fields()；默认走 ListFieldDictionary.fromCsv(Path).fields()。 */
    @FunctionalInterface
    public interface DictionarySource {
        List<ListField> load(Path path) throws Exception;
    }

    /** 解析单段视图 SQL；默认走 SqlViewParser.parse(viewName, sql)。 */
    @FunctionalInterface
    public interface ParseSource {
        ParseResult parse(String viewName, String sql);
    }

    private ViewSource viewSource;
    private GraphSource graphSource;
    private ParseSource parseSource;
    private DictionarySource dictionarySource = path -> {
        if (path == null) {
            throw new IOException("结算清单字段字典路径为空，字典不可用");
        }
        return ListFieldDictionary.fromCsv(path).fields();
    };

    private final ImpactStore store;
    private final AuditLog audit;
    private final IfaceRegistryClient client;
    private final Path writebackDir;
    private final RunStats stats;
    private final FailedEventLog failedLog;
    private final ImpactStateMachine machine;

    private boolean writeBackEnabled;

    // 会话内缓存：幂等键 -> 该次分析的完整记录集 / 输入上下文（供 confirm / withdraw 定位）
    private final Map<String, List<ImpactRecord>> cache = new LinkedHashMap<>();
    private final Map<String, InputContext> contexts = new LinkedHashMap<>();
    private final Map<String, List<ImpactRecord>> lastRecordsByView = new LinkedHashMap<>();

    public ImpactAnalysisService() {
        this(defaultStore(), defaultAudit(), null, null, null, null);
    }

    public ImpactAnalysisService(ImpactStore store, AuditLog audit) {
        this(store, audit, null, null, null, null);
    }

    public ImpactAnalysisService(ImpactStore store, AuditLog audit, IfaceRegistryClient client,
                                 Path writebackDir, RunStats stats, FailedEventLog failedLog) {
        this.store = Objects.requireNonNull(store, "store");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.client = client;
        this.writebackDir = writebackDir;
        this.stats = stats;
        this.failedLog = failedLog;
        this.machine = new ImpactStateMachine(store, audit);
    }

    public void setViewSource(ViewSource viewSource) { this.viewSource = viewSource; }
    public void setGraphSource(GraphSource graphSource) { this.graphSource = graphSource; }
    public void setParseSource(ParseSource parseSource) { this.parseSource = parseSource; }
    public void setDictionarySource(DictionarySource dictionarySource) { this.dictionarySource = dictionarySource; }

    /**
     * 主流程（九步编排）。每个降级分支只关掉受影响的步骤，其余步骤与导出仍可用。
     */
    public AnalysisResult analyze(AnalysisInput input) {
        Objects.requireNonNull(input, "input");
        String viewName = input.viewName();
        String changedField = input.changedField();

        boolean degraded = false;
        boolean graphStale = false;
        boolean dictAvailable = true;
        boolean parseFailed = false;
        boolean modelUnavailable = false;
        boolean dbUnavailable = false;
        List<String> notes = new ArrayList<>();
        ParseReport parseReport = new ParseReport();
        List<ManualReviewItem> reviewItems = new ArrayList<>();
        List<ImpactRecord> records = new ArrayList<>();
        String digest = "";

        // ---------- 步骤 1：读取视图定义 ----------
        String sql = null;
        try {
            sql = viewSource != null ? viewSource.read(viewName) : input.sql();
        } catch (Exception e) {
            dbUnavailable = true;
            degraded = true;
            graphStale = true;
            notes.add("库不可达：沿用上次已存图并标注「图已过期」，不推算新结论（"
                    + redact(e.getMessage()) + "）");
            bumpFailure();
            logFailure(input, "读取视图定义", "DB_UNREACHABLE", e);
        }

        if (!dbUnavailable) {
            digest = SqlDigest.digest(sql);

            // ---------- 步骤 2：SQL 解析 ----------
            ParseResult pr = parseSource != null
                    ? parseSource.parse(viewName, sql)
                    : new SqlViewParser().parse(viewName, sql);
            parseReport = pr.report();
            reviewItems = new ArrayList<>(pr.reviewItems());
            if (!pr.success()) {
                parseFailed = true;
                degraded = true;
                notes.add("解析失败：按行列号报告并给出待人工核对清单（本轮不推算新结论）");
                for (StructuredError se : pr.errors()) {
                    notes.add("  " + se.locatedMessage());
                }
                bumpFailure();
                logFailure(input, "SQL 解析", "PARSE_FAILURE",
                        new IllegalStateException("SQL 解析失败，需人工核对"));
            } else {
                if (stats != null) {
                    stats.incParseSuccess(Math.max(1, pr.report().successSegments()));
                }

                // ---------- 步骤 3：图构建 ----------
                FieldLineageGraph graph = graphSource != null
                        ? graphSource.build(List.of(pr))
                        : GraphBuilder.build(List.of(pr));

                // ---------- 步骤 4：下游可达 ----------
                FieldKey seed = FieldKey.of(viewName, changedField);
                ReachabilityResult reach = DownstreamReachability.reachable(graph, seed, input.scope());

                // ---------- 步骤 5：清单对齐 ----------
                List<ListField> dictFields = null;
                try {
                    List<ListField> loaded = dictionarySource == null
                            ? List.of() : dictionarySource.load(input.dictionaryPath());
                    if (loaded == null || loaded.isEmpty()) {
                        throw new IOException("字典字段为空");
                    }
                    dictFields = loaded;
                    if (stats != null) {
                        stats.incCompute();
                    }
                } catch (Exception e) {
                    dictAvailable = false;
                    degraded = true;
                    notes.add("字典不可用：清单字段区为空并报错，禁止按空字典计算影响面（"
                            + redact(e.getMessage()) + "）");
                    bumpFailure();
                    logFailure(input, "清单对齐", "DICT_UNAVAILABLE", e);
                }

                if (dictAvailable) {
                    records = new ArrayList<>(ImpactCalculator.calculate(
                            seed, reach, dictFields, digest, input.changeKind()));

                    // ---------- 步骤 6：归因 ----------
                    boolean[] attrDegraded = {false};
                    records = new ArrayList<>(attribute(input, records, notes, attrDegraded));
                    if (attrDegraded[0]) {
                        degraded = true;
                        modelUnavailable = true;
                    } else {
                        // 确定性路径：推进到 ATTRIBUTED
                        records = withStatus(records, ImpactStatus.ATTRIBUTED);

                        // ---------- 步骤 7：待确认 ----------
                        records = withStatus(records, ImpactStatus.PENDING_CONFIRM);
                        notes.add("待确认：记录状态 PENDING_CONFIRM，等待人工逐行确认");
                    }
                } else {
                    records = new ArrayList<>(); // 空清单字段区，非「无字段受影响」结论
                }
            }
        } else {
            // 库不可达：沿用上次已存记录（图已过期），不推算新结论
            records = new ArrayList<>(lastRecordsByView.getOrDefault(viewName, List.of()));
        }

        ImpactStatus status = degraded ? ImpactStatus.DEGRADED : ImpactStatus.PENDING_CONFIRM;
        String idem = SqlDigest.idempotencyKey(viewName, digest, changedField);

        // 闸门候选：未降级 + 有权限 + 开关开启（真正回写仍需逐行确认）
        writeBackEnabled = !degraded && input.permissionGranted() && input.writeSwitchOn();

        // 持久化代表记录（按幂等键覆盖，状态跨重启留存）+ 追加分析审计条目
        ImpactRecord representative = records.isEmpty()
                ? syntheticRecord(input, idem, status)
                : records.get(0);
        store.upsert(representative);
        audit.append(AuditLog.AuditEntry.builder()
                .operator(input.operator().isEmpty() ? "TOOL" : input.operator())
                .action("影响面分析")
                .view(viewName)
                .batch(representative.batch())
                .column(changedField)
                .section(representative.section())
                .listField(representative.listFieldName())
                .scope(input.scope().wireName())
                .idempotencyKey(idem)
                .toStatus(status.name())
                .detail(joinNotes(notes))
                .build());

        cache.put(idem, new ArrayList<>(records));
        contexts.put(idem, new InputContext(input, digest, joinNotes(notes)));
        if (!records.isEmpty() && !graphStale) {
            lastRecordsByView.put(viewName, new ArrayList<>(records));
        }

        String summary = buildSummary(viewName, changedField, status, records.size(), notes, idem);
        return resultBuilder(records, status, degraded, graphStale, writeBackEnabled,
                reviewItems, parseReport, summary,
                dictAvailable, parseFailed, modelUnavailable, dbUnavailable).build();
    }

    /**
     * 人工确认：逐行确认「实质受影响」判定，写 confirmed_by / confirmed_at；
     * CONFIRMED 只能由 Actor.HUMAN 推进；确认后若闸门通过且配置了客户端，尝试幂等回写（步骤 8/9）。
     */
    public AnalysisResult confirm(String idempotencyKey, String operator) {
        String op = operator == null || operator.isBlank() ? "human" : operator;
        List<ImpactRecord> recs = resolveRecords(idempotencyKey);
        if (recs.isEmpty()) {
            return resultBuilder(recs, ImpactStatus.PENDING_CONFIRM, false, false, false,
                    List.of(), new ParseReport(), "未找到待确认记录：" + idempotencyKey,
                    true, false, false, false).build();
        }
        String now = Instant.now().toString();
        List<ImpactRecord> confirmed = new ArrayList<>();
        for (ImpactRecord r : recs) {
            // 状态机裁决：CONFIRMED 仅人工可推进，并写库 + 追加审计条目
            ImpactRecord advanced = machine.apply(r, ImpactStatus.CONFIRMED,
                    ImpactStateMachine.Actor.HUMAN);
            ImpactRecord c = advanced.withHumanConfirmation(op, now);
            store.upsert(c);
            confirmed.add(c);
        }
        cache.put(idempotencyKey, confirmed);

        boolean wrote = false;
        InputContext ctx = contexts.get(idempotencyKey);
        if (ctx != null) {
            wrote = doWriteBack(idempotencyKey, confirmed, ctx, op);
        }
        // 回写成功后，缓存里已是 WRITTEN_BACK 且带 writeBackAt 的最新记录；
        // 返回给界面/导出的必须是这份，而不是确认瞬间的旧副本（否则状态与界面所见不一致）。
        List<ImpactRecord> returned = wrote
                ? cache.getOrDefault(idempotencyKey, confirmed) : confirmed;

        List<String> notes = new ArrayList<>();
        notes.add("人工确认：逐行写 confirmed_by / confirmed_at，状态 CONFIRMED（操作人 " + op + "）");
        notes.add(wrote ? "幂等回写：成功，状态 WRITTEN_BACK"
                : "幂等回写：未执行（未配置真实客户端或闸门未通过）");
        ImpactStatus status = wrote ? ImpactStatus.WRITTEN_BACK : ImpactStatus.CONFIRMED;
        return resultBuilder(returned, status, false, false, wrote,
                List.of(), new ParseReport(), buildSummary(
                        ctx == null ? "" : ctx.viewName, ctx == null ? "" : ctx.changedField,
                        status, returned.size(), notes, idempotencyKey),
                true, false, false, false).build();
    }

    /**
     * 人工撤回：只追加撤回审计条目，状态回 PENDING_CONFIRM，不物理删除原记录。
     */
    public AnalysisResult withdraw(String idempotencyKey, String operator) {
        String op = operator == null || operator.isBlank() ? "human" : operator;
        List<ImpactRecord> recs = resolveRecords(idempotencyKey);
        List<ImpactRecord> back = new ArrayList<>();
        for (ImpactRecord r : recs) {
            if (r.status() == ImpactStatus.CONFIRMED || r.status() == ImpactStatus.WRITTEN_BACK) {
                ImpactRecord b = machine.apply(r, ImpactStatus.PENDING_CONFIRM,
                        ImpactStateMachine.Actor.HUMAN);
                back.add(b);
            } else {
                back.add(r);
            }
        }
        // 只追加撤回条目，不删任何原条目
        audit.append(AuditLog.AuditEntry.builder()
                .operator(op)
                .action("撤回")
                .view(recs.isEmpty() ? "" : recs.get(0).viewName())
                .batch(recs.isEmpty() ? "" : recs.get(0).batch())
                .column(recs.isEmpty() ? "" : recs.get(0).changedField())
                .withdrawn(true)
                .idempotencyKey(idempotencyKey)
                .toStatus(ImpactStatus.PENDING_CONFIRM.name())
                .detail("人工撤回已确认记录，状态回到 PENDING_CONFIRM，不物理删除")
                .build());
        cache.put(idempotencyKey, back);
        List<String> notes = new ArrayList<>();
        notes.add("撤回：只追加审计条目，状态回 PENDING_CONFIRM，未物理删除");
        return resultBuilder(back, ImpactStatus.PENDING_CONFIRM, false, false, false,
                List.of(), new ParseReport(), buildSummary(
                        recs.isEmpty() ? "" : recs.get(0).viewName(),
                        recs.isEmpty() ? "" : recs.get(0).changedField(),
                        ImpactStatus.PENDING_CONFIRM, back.size(), notes, idempotencyKey),
                true, false, false, false).build();
    }

    /** 上一轮分析后是否具备回写条件（权限 + 开关 + 未降级）。 */
    public boolean writeBackEnabled() {
        return writeBackEnabled;
    }

    // ---- 内部实现 ----

    /** 步骤 6 归因：只通过 ModelGateway 接口 + AttributionService.merge/degrade。 */
    private List<ImpactRecord> attribute(AnalysisInput input, List<ImpactRecord> records,
                                         List<String> notes, boolean[] degradedOut) {
        ModelGateway gw = input.modelGateway();
        if (gw == null) {
            notes.add("归因：列名精确匹配走确定性层，未调用模型（可注入第二个实现）");
            return records;
        }
        try {
            AttributionRequest req = AttributionRequest.builder()
                    .viewName(input.viewName())
                    .changeKind(input.changeKind())
                    .changeNoteZh(input.changeNoteZh())
                    .build();
            AttributionResult result = gw.attribute(req);
            if (result.degraded()) {
                notes.add("模型不可达/降级：DEGRADED 且禁止回写（"
                        + nz(result.summaryZh()) + "）");
                degradedOut[0] = true;
                bumpFailure();
                return AttributionService.degrade(records);
            }
            boolean enabled = AttributionService.writeBackEnabled(result);
            notes.add("归因：模型返回结论，writeBackEnabled=" + enabled
                    + "（" + nz(result.summaryZh()) + "）");
            return AttributionService.merge(records, result);
        } catch (Exception e) {
            notes.add("模型不可达：DEGRADED 且禁止回写（" + redact(e.getMessage()) + "）");
            degradedOut[0] = true;
            bumpFailure();
            logFailure(input, "归因", "MODEL_UNAVAILABLE", e);
            return AttributionService.degrade(records);
        }
    }

    /** 步骤 8/9：守卫 + 幂等客户端；失败置 PENDING_WRITEBACK + 导出变更单。 */
    private boolean doWriteBack(String idem, List<ImpactRecord> confirmed, InputContext ctx, String operator) {
        GuardResult guard = WriteBackGuard.check(ctx.permissionGranted, ctx.writeSwitchOn, confirmed);
        if (!guard.allowed()) {
            audit.append(AuditLog.AuditEntry.builder()
                    .operator(operator).action("回写拦截").view(ctx.viewName)
                    .idempotencyKey(idem).detail("守卫拒绝：" + guard.reason()).build());
            return false;
        }
        if (client == null) {
            audit.append(AuditLog.AuditEntry.builder()
                    .operator(operator).action("回写跳过").view(ctx.viewName)
                    .idempotencyKey(idem).detail("未配置真实台账客户端，跳过真实回写（不伪造成功）").build());
            return false;
        }
        WriteBackRequest req = WriteBackRequest.fromRecords(confirmed, ctx.summaryZh);
        String path = IfaceRegistryClient.WRITE_IMPACTS_PATH_TEMPLATE
                .replace("{entry_id}", ctx.viewName);
        try {
            WriteResponse resp = client.post(path, req.toJson(), req.idempotencyKey());
            if (!resp.ok()) {
                throw new IfaceRegistryException("回写响应非 2xx，状态码 " + resp.status(), resp.status(), "");
            }
            List<ImpactRecord> written = new ArrayList<>();
            String now = Instant.now().toString();
            for (ImpactRecord c : confirmed) {
                ImpactRecord advanced = machine.apply(c, ImpactStatus.WRITTEN_BACK,
                        ImpactStateMachine.Actor.BACKGROUND);
                ImpactRecord w = advanced.withWriteBackAt(now);
                store.upsert(w);
                written.add(w);
            }
            cache.put(idem, written);
            if (stats != null) {
                stats.incWriteBackSuccess();
            }
            return true;
        } catch (IfaceRegistryException e) {
            // 仍失败：置 PENDING_WRITEBACK + 导出变更单 JSON，不静默丢失
            List<ImpactRecord> pending = new ArrayList<>();
            for (ImpactRecord c : confirmed) {
                pending.add(c.withStatus(ImpactStatus.PENDING_WRITEBACK));
            }
            cache.put(idem, pending);
            for (ImpactRecord p : pending) {
                store.upsert(p);
            }
            Path dir = writebackDir == null
                    ? Paths.get(System.getProperty("java.io.tmpdir")) : writebackDir;
            try {
                ChangeOrderExporter.export(req, dir);
            } catch (IOException io) {
                System.err.println("[ImpactAnalysisService] 变更单导出失败：" + io.getMessage());
            }
            bumpFailure();
            AnalysisInput mini = AnalysisInput.builder()
                    .viewName(ctx.viewName)
                    .changedField(ctx.changedField)
                    .sql("")
                    .build();
            logFailure(mini, "幂等回写", "WRITE_FAILED", e);
            audit.append(AuditLog.AuditEntry.builder()
                    .operator(operator).action("回写失败降级").view(ctx.viewName)
                    .idempotencyKey(idem).toStatus(ImpactStatus.PENDING_WRITEBACK.name())
                    .detail("回写仍失败，已置 PENDING_WRITEBACK 并导出变更单：" + redact(e.getMessage()))
                    .build());
            return false;
        }
    }

    private List<ImpactRecord> resolveRecords(String key) {
        List<ImpactRecord> cached = cache.get(key);
        if (cached != null && !cached.isEmpty()) {
            return new ArrayList<>(cached);
        }
        Optional<ImpactRecord> found = store.find(key);
        return found.map(List::of).orElseGet(List::of);
    }

    private static List<ImpactRecord> withStatus(List<ImpactRecord> records, ImpactStatus status) {
        List<ImpactRecord> out = new ArrayList<>(records.size());
        for (ImpactRecord r : records) {
            out.add(r.withStatus(status));
        }
        return out;
    }

    private static ImpactRecord syntheticRecord(AnalysisInput input, String idem, ImpactStatus status) {
        return ImpactRecord.builder()
                .idempotencyKey(idem)
                .viewName(input.viewName())
                .viewDialect("ORACLE")
                .sqlDigest(SqlDigest.digest(input.sql()))
                .changedField(input.changedField())
                .changeKind(input.changeKind())
                .section("")
                .listFieldName("")
                .sourceColumn("")
                .impact(ImpactType.SUSPECTED)
                .confidence(0.0d)
                .scope(input.scope())
                .hops(0)
                .batch("<EXAMPLE_BATCH>")
                .verdict(Verdict.NOT_ATTRIBUTED)
                .reasonZh("降级占位：未推算新结论")
                .status(status)
                .occurredAt(Instant.now().toString())
                .build();
    }

    private void logFailure(AnalysisInput input, String stage, String code, Throwable error) {
        if (failedLog == null) {
            return;
        }
        failedLog.append(FailedEventLog.FailedEvent.of(
                Instant.now().toString(),
                input == null ? "" : input.viewName(),
                stage, code,
                error == null ? "" : String.valueOf(error.getMessage()),
                input == null ? "" : SqlDigest.digest(input.sql()),
                input == null ? "" : SqlDigest.idempotencyKey(
                        input.viewName(), SqlDigest.digest(input.sql()), input.changedField())));
    }

    private void bumpFailure() {
        if (stats != null) {
            stats.incFailure();
        }
    }

    private static AnalysisResult.Builder resultBuilder(List<ImpactRecord> records, ImpactStatus status,
                                                        boolean degraded, boolean graphStale,
                                                        boolean wb, List<ManualReviewItem> reviews,
                                                        ParseReport report, String summary,
                                                        boolean dictAvailable, boolean parseFailed,
                                                        boolean modelUnavailable, boolean dbUnavailable) {
        return AnalysisResult.builder()
                .records(records)
                .status(status)
                .degraded(degraded)
                .graphStale(graphStale)
                .writeBackEnabled(wb)
                .reviewItems(reviews)
                .parseReport(report)
                .steps(AnalysisResult.STEPS)
                .summary(summary)
                .dictionaryAvailable(dictAvailable)
                .parseFailed(parseFailed)
                .modelUnavailable(modelUnavailable)
                .dbUnavailable(dbUnavailable);
    }

    private static String buildSummary(String viewName, String changedField, ImpactStatus status,
                                       int recordCount, List<String> notes, String idem) {
        StringBuilder sb = new StringBuilder();
        sb.append("视图 ").append(viewName).append(".").append(changedField)
                .append("｜状态 ").append(status.name())
                .append("｜记录 ").append(recordCount).append(" 条｜幂等键 ").append(idem);
        for (String n : notes) {
            sb.append(System.lineSeparator()).append("· ").append(n);
        }
        return sb.toString();
    }

    private static String joinNotes(List<String> notes) {
        return notes.isEmpty() ? "无" : String.join("；", notes);
    }

    private static String redact(String s) {
        return SecretRedactor.empty().redact(s == null ? "" : s);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static ImpactStore defaultStore() {
        try {
            Path dir = Files.createTempDirectory("medins-impact-");
            return new ImpactStore(dir.resolve("impact.db"));
        } catch (IOException e) {
            throw new IllegalStateException("无法创建默认影响面库", e);
        }
    }

    private static AuditLog defaultAudit() {
        try {
            Path dir = Files.createTempDirectory("medins-audit-");
            return new AuditLog(dir.resolve("audit.log"));
        } catch (IOException e) {
            throw new IllegalStateException("无法创建默认审计链", e);
        }
    }

    /** 保存确认 / 回写阶段所需的输入上下文。 */
    private static final class InputContext {
        final boolean permissionGranted;
        final boolean writeSwitchOn;
        final String viewName;
        final String changedField;
        final String summaryZh;

        InputContext(AnalysisInput input, String digest, String notes) {
            this.permissionGranted = input.permissionGranted();
            this.writeSwitchOn = input.writeSwitchOn();
            this.viewName = input.viewName();
            this.changedField = input.changedField();
            this.summaryZh = "影响面回写草稿｜" + notes;
        }
    }
}
