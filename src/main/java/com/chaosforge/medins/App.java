package com.chaosforge.medins;

import com.chaosforge.medins.adapter.ChangeOrderExporter;
import com.chaosforge.medins.adapter.WriteBackRequest;
import com.chaosforge.medins.catalog.AlignmentResult;
import com.chaosforge.medins.catalog.FieldAligner;
import com.chaosforge.medins.catalog.ListFieldDictionary;
import com.chaosforge.medins.config.AppConfig;
import com.chaosforge.medins.config.EnvSource;
import com.chaosforge.medins.lineage.FieldLineageGraph;
import com.chaosforge.medins.lineage.GraphBuilder;
import com.chaosforge.medins.model.ChangeKind;
import com.chaosforge.medins.model.EdgeKind;
import com.chaosforge.medins.model.FieldKey;
import com.chaosforge.medins.model.ImpactRecord;
import com.chaosforge.medins.model.ImpactScope;
import com.chaosforge.medins.model.ListField;
import com.chaosforge.medins.report.DiffResult;
import com.chaosforge.medins.report.ImpactCsvWriter;
import com.chaosforge.medins.report.NoteContext;
import com.chaosforge.medins.report.TwoVersionDiff;
import com.chaosforge.medins.report.VendorNoteWriter;
import com.chaosforge.medins.service.AnalysisInput;
import com.chaosforge.medins.service.AnalysisResult;
import com.chaosforge.medins.service.ImpactAnalysisService;
import com.chaosforge.medins.sqlparse.ColumnEdge;
import com.chaosforge.medins.sqlparse.ManualReviewItem;
import com.chaosforge.medins.sqlparse.ParseResult;
import com.chaosforge.medins.sqlparse.SampleViews;
import com.chaosforge.medins.sqlparse.SqlDigest;
import com.chaosforge.medins.sqlparse.SqlViewParser;
import com.chaosforge.medins.sqlparse.StructuredError;
import com.chaosforge.medins.ui.HeadlessMode;
import com.chaosforge.medins.ui.MainFrame;
import com.chaosforge.medins.ui.Theme;
import com.chaosforge.medins.ui.TrayController;
import com.chaosforge.medins.ui.UiModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 入口：桌面窗口 + 托盘常驻是产品正常形态；命令行只作为运维与排障通道（CI 自检、无人值守跑批）。
 *
 * <p>子命令：
 * <ul>
 *   <li>{@code parse-view --sql <file> --out <json>}：把一段视图 SQL 解析成可核对的列级边 JSON；</li>
 *   <li>{@code analyze --view ... --sql ... --field ... [--scope strict|conservative] [--out csv] [--note md]}：跑主流程并导出；</li>
 *   <li>{@code export --kind review|change-order ...}：导出待人工核对清单或变更单；</li>
 *   <li>{@code diff --old-sql ... --new-sql ...}：两版取数视图的影响面差异回放；</li>
 *   <li>{@code doctor / version / --help}：自检与帮助。</li>
 * </ul>
 *
 * <p>无显示环境下自动进入 headless 模式：不创建窗口，仍可完整执行全部 CLI 子命令。
 */
public final class App {

    /** 产品中文名，与 README 标题一致。 */
    public static final String DISPLAY_NAME = "医保结算清单取数影响分析";

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private App() { }

    public static void main(String[] args) {
        if (args.length == 0 && !HeadlessMode.isHeadless()) {
            int code = launchGui();
            if (code != 0) System.exit(code);
            return;
        }
        int code = execute(args);
        if (code != 0) System.exit(code);
    }

    /**
     * 执行一次子命令，返回退出码。测试与 CI 直接调用本方法，不经过 {@link System#exit}。
     */
    public static int execute(String[] args) {
        if (args == null || args.length == 0) {
            printUsage();
            if (HeadlessMode.isHeadless()) {
                System.out.println();
                System.out.println("当前为无显示环境（headless）：桌面窗口未创建，全部 CLI 子命令可用。");
            }
            return 0;
        }
        String command = args[0];
        Map<String, String> opts = options(args, 1);
        try {
            return switch (command) {
                case "version" -> { printVersion(); yield 0; }
                case "doctor" -> runDoctor();
                case "parse-view" -> runParseView(opts);
                case "analyze" -> runAnalyze(opts);
                case "export" -> runExport(opts);
                case "diff" -> runDiff(opts);
                case "gui" -> launchGui();
                case "--help", "-h", "help" -> { printUsage(); yield 0; }
                default -> {
                    System.err.println("未知子命令：" + command);
                    printUsage();
                    yield 2;
                }
            };
        } catch (Exception e) {
            System.err.println("执行失败：" + e.getClass().getSimpleName() + "：" + e.getMessage());
            return 1;
        }
    }

    // ------------------------------------------------------------------ //
    // parse-view：最窄主路径，输入 → 处理 → 可核对产出
    // ------------------------------------------------------------------ //

    private static int runParseView(Map<String, String> opts) throws IOException {
        if (opts.containsKey("--help")) { printParseViewHelp(); return 0; }
        String sqlPath = opts.get("--sql");
        String outPath = opts.get("--out");
        if (isBlank(sqlPath) || isBlank(outPath)) {
            System.err.println("parse-view 需要 --sql <file> --out <json>");
            printParseViewHelp();
            return 2;
        }
        Path sqlFile = Paths.get(sqlPath);
        if (!Files.isRegularFile(sqlFile)) {
            System.err.println("SQL 文件不存在：" + sqlPath);
            return 2;
        }
        String sql = Files.readString(sqlFile, StandardCharsets.UTF_8);
        String viewName = viewNameOf(sqlFile.getFileName().toString());
        ParseResult result = new SqlViewParser().parse(viewName, sql);
        Path out = Paths.get(outPath);
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        JSON.writeValue(out.toFile(), result.toMap());

        System.out.println("视图：" + result.viewName() + "，方言：" + result.dialect().labelZh());
        System.out.println("SQL 摘要：" + result.sqlDigest());
        System.out.println("列级边：" + result.edges().size()
                + " 条（直取 " + result.edgesOfKind(EdgeKind.DIRECT).size()
                + " / 派生 " + result.edgesOfKind(EdgeKind.DERIVED).size()
                + " / 聚合 " + result.edgesOfKind(EdgeKind.AGGREGATE).size()
                + " / 过滤 " + result.edgesOfKind(EdgeKind.FILTER).size() + "）");
        System.out.println("待人工核对：" + result.reviewItems().size() + " 条");
        System.out.println("已写出：" + out.toAbsolutePath());
        if (!result.success()) {
            for (StructuredError error : result.errors()) {
                System.err.println("解析失败：" + error.locatedMessage() + "（" + error.code() + "）");
            }
            return 1;
        }
        return 0;
    }

    // ------------------------------------------------------------------ //
    // analyze：九步主流程 + 可带走产物
    // ------------------------------------------------------------------ //

    private static int runAnalyze(Map<String, String> opts) throws IOException {
        if (opts.containsKey("--help")) { printAnalyzeHelp(); return 0; }
        AnalysisOutcome outcome = analyze(opts);
        if (outcome == null) return 2;
        AnalysisResult result = outcome.result;

        System.out.println(DISPLAY_NAME + " · 主流程结果");
        System.out.println("视图 / 改动列：" + outcome.view + " / " + outcome.field);
        System.out.println("口径：" + outcome.scope.labelZh() + "（" + outcome.scope.wireName() + "）");
        System.out.println("SQL 摘要：" + SqlDigest.digest(outcome.sql));
        System.out.println("影响面记录：" + result.records().size() + " 行");
        System.out.println("状态：" + result.status().labelZh()
                + "，降级：" + (result.degraded() ? "是" : "否")
                + "，待人工核对：" + result.reviewItems().size() + " 条");
        System.out.println("回写开关：" + (result.writeBackEnabled() ? "开启" : "关闭（默认）"));
        System.out.println("主流程步骤：" + String.join(" → ", result.steps()));
        if (!result.summary().isBlank()) System.out.println(result.summary());

        if (!isBlank(opts.get("--out"))) {
            Path csv = Paths.get(opts.get("--out"));
            writeWithParent(csv, writer -> ImpactCsvWriter.write(csv, result.records()));
            System.out.println("影响面 CSV 已写出：" + csv.toAbsolutePath());
        }
        if (!isBlank(opts.get("--note"))) {
            Path note = Paths.get(opts.get("--note"));
            String text = VendorNoteWriter.write(result.records(), noteContext(outcome, result));
            writeWithParent(note, writer -> Files.writeString(note, text, StandardCharsets.UTF_8));
            System.out.println("厂商说明草稿已写出：" + note.toAbsolutePath());
        }
        return 0;
    }

    /** 一次分析的可复用结果（供 export / diff 复用同一条计算路径）。 */
    private record AnalysisOutcome(AnalysisResult result, String view, String field, String sql,
                                   ImpactScope scope, String changeNote, ChangeKind changeKind) { }

    private static AnalysisOutcome analyze(Map<String, String> opts) throws IOException {
        String view = upper(opts.getOrDefault("--view", "V_BILL_FEE"));
        String field = upper(opts.getOrDefault("--field", "FEE_AMT"));
        ImpactScope scope = "conservative".equalsIgnoreCase(opts.get("--scope"))
                ? ImpactScope.CONSERVATIVE : ImpactScope.STRICT;
        String changeNote = opts.getOrDefault("--note-zh", "取数视图口径调整，需核对结算清单波及面");
        ChangeKind changeKind = parseChangeKind(opts.get("--change-kind"));

        String sql;
        String sqlPath = opts.get("--sql");
        if (!isBlank(sqlPath)) {
            Path p = Paths.get(sqlPath);
            if (!Files.isRegularFile(p)) {
                System.err.println("SQL 文件不存在：" + sqlPath);
                return null;
            }
            sql = Files.readString(p, StandardCharsets.UTF_8);
        } else {
            try {
                sql = SampleViews.load(view.toLowerCase(java.util.Locale.ROOT));
            } catch (IOException e) {
                System.err.println("未指定 --sql，且样例视图不存在：" + view);
                return null;
            }
        }

        ImpactAnalysisService service = new ImpactAnalysisService();
        service.setDictionarySource(path -> dictionary().fields());
        service.setGraphSource(parsed -> withListFieldEdges(GraphBuilder.build(parsed), parsed));

        AnalysisInput input = AnalysisInput.builder()
                .viewName(view)
                .sql(sql)
                .changedField(field)
                .changeKind(changeKind)
                .changeNoteZh(changeNote)
                .dictionaryPath(Paths.get("src/main/resources/samples/settlement_fields.csv"))
                .scope(scope)
                .writeSwitchOn(false)
                .operator("CLI")
                .permissionGranted(false)
                .build();
        return new AnalysisOutcome(service.analyze(input), view, field, sql, scope, changeNote, changeKind);
    }

    /**
     * 把「视图输出列 → 结算清单字段」的对齐结果补成图上的一等边，
     * 于是「改这个输出列」沿 DOWNSTREAM 就能到达清单字段节点，主路径端到端可核对。
     * 一对多歧义不补边（拒绝自动合并）。
     */
    private static FieldLineageGraph withListFieldEdges(FieldLineageGraph graph, List<ParseResult> parsed) {
        ListFieldDictionary dict;
        try {
            dict = dictionary();
        } catch (IOException e) {
            return graph;
        }
        FieldAligner aligner = new FieldAligner();
        for (ParseResult pr : parsed) {
            for (ColumnEdge edge : pr.edges()) {
                if (edge.unresolved()) continue;
                AlignmentResult aligned = aligner.align(edge.outputColumn(), dict);
                if (aligned.group() == AlignmentResult.Group.AMBIGUOUS || aligned.matches().isEmpty()) continue;
                FieldKey from = FieldKey.of(pr.viewName(), edge.outputColumn());
                for (ListField lf : aligned.matches()) {
                    graph.addEdge(from, FieldKey.of("LIST_FIELD", lf.fieldName()), EdgeKind.DIRECT, "清单对齐");
                }
            }
        }
        return graph;
    }

    private static NoteContext noteContext(AnalysisOutcome outcome, AnalysisResult result) {
        Map<String, String> evidence = new LinkedHashMap<>();
        for (ImpactRecord r : result.records()) {
            evidence.put(r.listFieldName(), "沿 " + r.hops() + " 跳影响 " + r.listFieldName()
                    + "（" + r.scope().labelZh() + "，置信度 " + String.format("%.2f", r.confidence()) + "）");
        }
        return NoteContext.builder()
                .viewName(outcome.view)
                .changedField(outcome.field)
                .changeKind(outcome.changeKind)
                .changeNoteZh(outcome.changeNote)
                .sqlDigest(SqlDigest.digest(outcome.sql))
                .scope(outcome.scope)
                .degraded(result.degraded())
                .evidence(evidence)
                .build();
    }

    // ------------------------------------------------------------------ //
    // export / diff
    // ------------------------------------------------------------------ //

    private static int runExport(Map<String, String> opts) throws IOException {
        String kind = opts.getOrDefault("--kind", "review");
        if (opts.containsKey("--help")) { printExportHelp(); return 0; }
        if ("review".equalsIgnoreCase(kind)) {
            String sqlPath = opts.get("--sql");
            if (isBlank(sqlPath)) {
                System.err.println("export --kind review 需要 --sql <file>");
                return 2;
            }
            Path p = Paths.get(sqlPath);
            if (!Files.isRegularFile(p)) { System.err.println("SQL 文件不存在：" + sqlPath); return 2; }
            String sql = Files.readString(p, StandardCharsets.UTF_8);
            ParseResult result = new SqlViewParser().parse(viewNameOf(p.getFileName().toString()), sql);
            List<Map<String, Object>> items = new ArrayList<>();
            for (ManualReviewItem item : result.reviewItems()) items.add(item.toMap());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("view_name", result.viewName());
            payload.put("sql_digest", result.sqlDigest());
            payload.put("review_items", items);
            payload.put("count", items.size());
            String text = JSON.writeValueAsString(payload);
            if (isBlank(opts.get("--out"))) {
                System.out.println(text);
            } else {
                Path out = Paths.get(opts.get("--out"));
                writeWithParent(out, w -> Files.writeString(out, text, StandardCharsets.UTF_8));
                System.out.println("待人工核对清单已写出：" + out.toAbsolutePath() + "（" + items.size() + " 条）");
            }
            return 0;
        }
        if ("change-order".equalsIgnoreCase(kind)) {
            AnalysisOutcome outcome = analyze(opts);
            if (outcome == null) return 2;
            WriteBackRequest request = outcome.result.records().isEmpty()
                    ? WriteBackRequest.builder()
                        .idempotencyKey(SqlDigest.idempotencyKey(outcome.view,
                                SqlDigest.digest(outcome.sql), outcome.field))
                        .viewName(outcome.view).viewDialect("oracle")
                        .sqlDigest(SqlDigest.digest(outcome.sql)).changedField(outcome.field)
                        .changeKind(outcome.changeKind.wireName())
                        .summaryZh("待人工录入院内台账：本轮无已确认影响面记录")
                        .build()
                    : WriteBackRequest.fromRecords(outcome.result.records(),
                            VendorNoteWriter.write(outcome.result.records(), noteContext(outcome, outcome.result)));
            Path dir = isBlank(opts.get("--out")) ? Paths.get("data") : Paths.get(opts.get("--out"));
            Path file = ChangeOrderExporter.export(request, dir);
            System.out.println("变更单已导出：" + file.toAbsolutePath() + "（待人工录入院内台账）");
            return 0;
        }
        System.err.println("未知导出类型：" + kind + "（可选 review / change-order）");
        return 2;
    }

    private static int runDiff(Map<String, String> opts) throws IOException {
        if (opts.containsKey("--help")) { printDiffHelp(); return 0; }
        String oldSql = opts.get("--old-sql");
        String newSql = opts.get("--new-sql");
        if (isBlank(oldSql) || isBlank(newSql)) {
            System.err.println("diff 需要 --old-sql <file> --new-sql <file>");
            return 2;
        }
        Map<String, String> oldOpts = new LinkedHashMap<>(opts);
        oldOpts.put("--sql", oldSql);
        Map<String, String> newOpts = new LinkedHashMap<>(opts);
        newOpts.put("--sql", newSql);
        AnalysisOutcome before = analyze(oldOpts);
        AnalysisOutcome after = analyze(newOpts);
        if (before == null || after == null) return 2;
        DiffResult diff = TwoVersionDiff.compare(before.result.records(), after.result.records());
        System.out.println(diff.toZh());
        if (!isBlank(opts.get("--out"))) {
            Path out = Paths.get(opts.get("--out"));
            writeWithParent(out, w -> Files.writeString(out, diff.toCsv(), StandardCharsets.UTF_8));
            System.out.println("差异清单已写出：" + out.toAbsolutePath());
        }
        return 0;
    }

    // ------------------------------------------------------------------ //
    // doctor / version / GUI
    // ------------------------------------------------------------------ //

    private static int runDoctor() {
        printVersion();
        System.out.println();
        System.out.println("环境变量自检（只报变量名与是否已设，不回显取值）：");
        List<AppConfig.EnvVarStatus> statuses = AppConfig.inspect(EnvSource.system());
        boolean allPresent = true;
        for (AppConfig.EnvVarStatus status : statuses) {
            if (!status.present()) allPresent = false;
            System.out.printf("  %-28s %-8s %s%n", status.name(),
                    status.present() ? "[已设置]" : "[未设置]", status.note());
        }
        System.out.println();
        System.out.println("运行时：Java " + System.getProperty("java.version") + "（需要 17 及以上）");
        System.out.println("驱动：Druid " + driverStatus("com.alibaba.druid.sql.SQLUtils")
                + " / SQLite JDBC " + driverStatus("org.sqlite.JDBC"));
        System.out.println("显示环境：" + (HeadlessMode.isHeadless() ? "headless（不创建窗口）" : "桌面可用"));
        System.out.println("连通性：院内 HIS 只读库 / 院内接口管理平台 / 模型端点本轮均未实连，仅做协议兼容测试。");
        if (!allPresent) {
            System.out.println();
            System.out.println("有变量尚未设置：请在系统环境或 .env 中补齐后重试，详见 .env.example。");
            return 1;
        }
        System.out.println("全部环境变量已就位。");
        return 0;
    }

    private static String driverStatus(String className) {
        try {
            Class.forName(className);
            return "可用";
        } catch (ClassNotFoundException e) {
            return "不可用";
        }
    }

    private static String printVersion() {
        System.out.println(DISPLAY_NAME + " v" + version());
        return "";
    }

    private static String version() {
        String v = App.class.getPackage().getImplementationVersion();
        return v == null ? "0.1.0" : v;
    }

    private static int launchGui() {
        if (HeadlessMode.isHeadless()) {
            System.out.println("无显示环境：已进入 headless 模式，未创建桌面窗口；CLI 子命令完整可用。");
            return 0;
        }
        try {
            Theme.apply();
            // 打开窗口即载入脱敏样例视图并跑一次影响面分析，界面上的数字与表格都是真实产物
            Map<String, String> guiOpts = new LinkedHashMap<>();
            guiOpts.put("--view", "V_BILL_FEE");
            guiOpts.put("--field", "FEE_AMT");
            AnalysisOutcome outcome = analyze(guiOpts);
            AnalysisResult analysis = outcome == null ? null : outcome.result;
            List<List<String>> rows = new ArrayList<>();
            if (analysis != null) {
                for (ImpactRecord r : analysis.records()) {
                    rows.add(List.of(r.section(), r.listFieldName(), r.sourceColumn(),
                            r.verdict() == null ? "" : r.verdict().labelZh(),
                            String.format(java.util.Locale.ROOT, "%.2f", r.confidence()),
                            r.scope().labelZh(), String.valueOf(r.hops()), r.batch()));
                }
            }
            StringBuilder detail = new StringBuilder("影响面详情 · 逐跳回溯\n\n");
            if (analysis != null) {
                for (ImpactRecord r : analysis.records()) {
                    detail.append("清单字段：").append(r.section()).append(" / ")
                            .append(r.listFieldName()).append("\n")
                            .append("来源列：").append(r.sourceColumn()).append("\n")
                            .append("逐跳路径：").append(outcome.view).append(".")
                            .append(outcome.field).append(" → LIST_FIELD.")
                            .append(r.listFieldName()).append("\n")
                            .append("每跳边类型：直取 DIRECT｜跳数：").append(r.hops()).append("\n")
                            .append("口径：").append(r.scope().labelZh())
                            .append("｜置信度：")
                            .append(String.format(java.util.Locale.ROOT, "%.2f", r.confidence()))
                            .append("\n")
                            .append("判定：").append(r.verdict() == null ? "" : r.verdict().labelZh())
                            .append("\n理由：").append(r.reasonZh()).append("\n\n");
                }
                detail.append("待人工核对条目：").append(analysis.reviewItems().size()).append(" 条\n")
                        .append("批次：<EXAMPLE_BATCH>（样例占位）\n")
                        .append("状态：").append(analysis.status().labelZh())
                        .append("｜回写开关：关闭（默认）");
            } else {
                detail.append("载入样例失败，请用 doctor 自检或改用 CLI 子命令。");
            }
            Map<String, Integer> counters = new LinkedHashMap<>();
            counters.put("解析", SampleViews.count());
            counters.put("计算", rows.size());
            counters.put("失败", analysis == null ? 1 : analysis.parseReport().failedSegments());
            String statusText = analysis == null
                    ? "载入样例失败，请用 doctor 自检或改用 CLI 子命令"
                    : outcome.view + "." + outcome.field + "｜" + rows.size()
                        + " 行影响面｜" + analysis.status().labelZh() + "｜写类动作关闭";
            UiModel model = UiModel.builder()
                    .panels(List.of("顶栏计数", "左视图树", "中央影响面表", "右详情抽屉", "底部状态条"))
                    .topCounters(counters)
                    .statusText(statusText)
                    .writeSwitchOn(false)
                    .modelAvailable(false)
                    .banner(UiModel.BANNER_NO_MODEL)
                    .tableColumns(List.of("分区", "字段", "来源列", "判定", "置信度", "口径", "跳数", "批次"))
                    .detailSections(List.of("证据链", "口径与置信度", "待人工核对"))
                    .trayMenu(List.of("打开主窗口", "立即重算", "导出变更单", "退出"))
                    .loadingText("正在计算影响面，请稍候…")
                    .emptyText("尚未选择视图，请从左侧选择取数视图")
                    .errorText("计算失败，请查看状态条说明并重试")
                    .toastKinds(List.of("成功", "警告", "失败"))
                    .confirmableActions(List.of("撤回人工确认", "开启写类开关", "批量标记为已确认"))
                    .splitRatios(List.of(18, 56, 26))
                    .viewTreeNodes(SampleViews.VIEW_NAMES.stream()
                            .map(v -> v.toUpperCase(java.util.Locale.ROOT)).toList())
                    .tableRows(rows)
                    .detailText(detail.toString())
                    .build();
            MainFrame frame = new MainFrame(model);
            frame.buildComponents();
            frame.setSize(1120, 700);
            frame.setLocation(60, 60);
            frame.setVisible(true);
            frame.toFront();
            TrayController tray = new TrayController();
            tray.install();
            tray.hideOnClose();
            if (tray.degraded()) System.out.println(tray.degradedHint());
            return 0;
        } catch (Throwable t) {
            System.err.println("桌面窗口启动失败：" + t.getMessage() + "；可改用 CLI 子命令（doctor / analyze）。");
            return 1;
        }
    }

    // ------------------------------------------------------------------ //
    // 工具方法
    // ------------------------------------------------------------------ //

    private static ListFieldDictionary dictionary() throws IOException {
        String path = "samples/settlement_fields.csv";
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IOException("清单字段字典资源不存在：" + path);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return ListFieldDictionary.fromCsv(reader);
            }
        }
    }

    private static Map<String, String> options(String[] args, int from) {
        Map<String, String> opts = new LinkedHashMap<>();
        for (int i = from; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--")) {
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    opts.put(arg, args[++i]);
                } else {
                    opts.put(arg, "true");
                }
            }
        }
        return opts;
    }

    private static String viewNameOf(String fileName) {
        String name = fileName;
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return upper(name);
    }

    private static String upper(String s) {
        return s == null ? "" : s.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static ChangeKind parseChangeKind(String raw) {
        if (isBlank(raw)) return ChangeKind.LOGIC_CHANGED;
        try {
            return ChangeKind.fromWireName(raw);
        } catch (RuntimeException e) {
            return ChangeKind.LOGIC_CHANGED;
        }
    }

    @FunctionalInterface
    private interface FileAction { void run(Object ignored) throws IOException; }

    private static void writeWithParent(Path path, FileAction action) throws IOException {
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        action.run(null);
    }

    private static void printUsage() {
        System.out.println(DISPLAY_NAME);
        System.out.println();
        System.out.println("用法：java -jar med-insurance-settlement-impact.jar <子命令> [参数]");
        System.out.println();
        System.out.println("无参数启动时（有显示环境）打开三栏工作台并常驻托盘；无显示环境自动进入 headless 模式。");
        System.out.println();
        System.out.println("  parse-view   把一段视图 SQL 解析成列级边 JSON");
        System.out.println("  analyze      跑完整影响面主流程，可导出 CSV 与厂商说明");
        System.out.println("  export       导出待人工核对清单 / 变更单");
        System.out.println("  diff         两版取数视图的影响面差异回放");
        System.out.println("  doctor       环境变量、运行时与驱动自检（不回显凭据）");
        System.out.println("  version      打印版本号");
        System.out.println("  --help       查看本帮助；各子命令也支持 --help");
        System.out.println();
        System.out.println("完整命令参考见 docs/cli.md；样例输出见 docs/examples.md。");
    }

    private static void printParseViewHelp() {
        System.out.println("parse-view --sql <file> --out <edges.json>");
        System.out.println("输出 JSON 含 view_name / sql_digest（64 位十六进制）/ edges[] / review_items[] / report。");
    }

    private static void printAnalyzeHelp() {
        System.out.println("analyze [--view V_BILL_FEE] [--sql <file>] [--field FEE_AMT]");
        System.out.println("        [--scope strict|conservative] [--out impact.csv] [--note vendor-note.md]");
        System.out.println("        [--change-kind renamed|logic_changed|filter_added|dropped|added] [--note-zh 说明]");
        System.out.println("不指定 --sql 时读取内置脱敏样例视图；不指定 --out/--note 时只打印摘要。");
        System.out.println("写类开关默认关闭，未经人工逐行确认不会构造任何回写请求。");
    }

    private static void printExportHelp() {
        System.out.println("export --kind review --sql <file> [--out review.json]");
        System.out.println("export --kind change-order [--sql <file>] [--out <dir>]");
    }

    private static void printDiffHelp() {
        System.out.println("diff --old-sql <file> --new-sql <file> [--out diff.csv]");
    }
}
