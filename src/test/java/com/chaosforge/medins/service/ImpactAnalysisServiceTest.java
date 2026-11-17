package com.chaosforge.medins.service;

import com.chaosforge.medins.adapter.HttpTransport;
import com.chaosforge.medins.adapter.IfaceRegistryClient;
import com.chaosforge.medins.adapter.RawResponse;
import com.chaosforge.medins.attribution.AttributionRequest;
import com.chaosforge.medins.attribution.AttributionResult;
import com.chaosforge.medins.attribution.ModelGateway;
import com.chaosforge.medins.lineage.FieldLineageGraph;
import com.chaosforge.medins.model.ChangeKind;
import com.chaosforge.medins.model.EdgeKind;
import com.chaosforge.medins.model.FieldKey;
import com.chaosforge.medins.model.ImpactRecord;
import com.chaosforge.medins.model.ImpactScope;
import com.chaosforge.medins.model.ImpactStatus;
import com.chaosforge.medins.model.ListField;
import com.chaosforge.medins.sqlparse.ManualReviewItem;
import com.chaosforge.medins.sqlparse.ParseResult;
import com.chaosforge.medins.sqlparse.SqlDialect;
import com.chaosforge.medins.sqlparse.StructuredError;
import com.chaosforge.medins.store.AuditLog;
import com.chaosforge.medins.store.ImpactStore;
import com.chaosforge.medins.store.RunStats;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 46：影响面分析主流程编排与分步降级、人工确认、撤回。
 * 用可注入的 ViewSource / ParseSource / GraphSource / DictionarySource 做确定性替身；
 * 其余一条用例走默认 DictionarySource（真实 fromCsv(Path)）以证明主链路接通。
 */
class ImpactAnalysisServiceTest {

    @TempDir
    Path dir;

    private AnalysisInput.Builder baseInput() {
        return AnalysisInput.builder()
                .viewName("V_BILL_FEE")
                .sql("SELECT o.o_orderkey AS bill_no FROM orders o")
                .changedField("FEE_AMT")
                .changeKind(ChangeKind.LOGIC_CHANGED)
                .changeNoteZh("费用口径调整")
                .scope(ImpactScope.STRICT)
                .permissionGranted(true)
                .writeSwitchOn(false)
                .operator("专员A");
    }

    /** 注入除字典外的确定性来源。 */
    private void injectCommon(ImpactAnalysisService s) {
        FieldKey seed = FieldKey.of("V_BILL_FEE", "FEE_AMT");
        s.setViewSource(v -> "SELECT o.o_orderkey AS bill_no FROM orders o");
        s.setParseSource((v, sql) -> ParseResult.success(
                v, SqlDialect.ORACLE, "DIGEST", List.of(), List.of()));
        s.setGraphSource(parsed -> {
            FieldLineageGraph g = new FieldLineageGraph();
            g.addEdge(seed, FieldKey.of("V_BILL_FEE", "BILL_NO"), EdgeKind.DIRECT, "V_BILL_FEE");
            g.addEdge(seed, FieldKey.of("V_BILL_FEE", "PATIENT_ID"), EdgeKind.DIRECT, "V_BILL_FEE");
            return g;
        });
    }

    /** 注入固定清单字段字典（不依赖 fromCsv 行为）。 */
    private void injectFixedDict(ImpactAnalysisService s) {
        s.setDictionarySource(path -> List.of(
                new ListField("基本信息", "BILL_NO", "B01", "字符", true, "口径"),
                new ListField("基本信息", "PATIENT_ID", "B02", "字符", true, "口径")));
    }

    @Test
    void nineStepsInOrder_realCsvDictionary_andRepresentativePersisted() throws Exception {
        Path csv = dir.resolve("dict.csv");
        Files.writeString(csv,
                "section,field_name,field_code,value_type,required_flag,basis_note\n"
                        + "基本信息,BILL_NO,B01,字符,true,口径\n"
                        + "基本信息,PATIENT_ID,B02,字符,true,口径\n",
                StandardCharsets.UTF_8);

        try (Fixture f = new Fixture(dir, null)) {
            injectCommon(f.service); // 不注入 DictionarySource → 走默认 ListFieldDictionary.fromCsv(Path)
            AnalysisResult r = f.service.analyze(baseInput().dictionaryPath(csv).build());

            assertEquals(AnalysisResult.STEPS, r.steps(), "九步顺序固定");
            assertEquals(9, r.steps().size());
            assertEquals("读取视图定义", r.steps().get(0));
            assertEquals("SQL 解析", r.steps().get(1));
            assertEquals("图构建", r.steps().get(2));
            assertEquals("下游可达", r.steps().get(3));
            assertEquals("清单对齐", r.steps().get(4));
            assertEquals("归因", r.steps().get(5));
            assertEquals("待确认", r.steps().get(6));
            assertEquals("人工确认", r.steps().get(7));
            assertEquals("幂等回写", r.steps().get(8));

            assertFalse(r.degraded());
            assertEquals(ImpactStatus.PENDING_CONFIRM, r.status());
            assertTrue(r.records().size() >= 2, "主流程产出影响面记录");
            assertTrue(r.parseReport().totalSegments() >= 1);
            assertTrue(f.store.all().size() >= 1, "代表记录已落库");
            assertFalse(f.service.writeBackEnabled(), "写类开关默认关闭时不具备回写");
        }
    }

    @Test
    void dbUnreachable_usesStaleGraphNoNewConclusions() {
        try (Fixture f = new Fixture(dir, null)) {
            injectCommon(f.service);
            injectFixedDict(f.service);
            AnalysisResult first = f.service.analyze(baseInput().build());
            assertFalse(first.degraded());
            int n = first.records().size();
            assertTrue(n >= 1);

            // 库不可达：沿用上次已存图/记录，标注「图已过期」，不推算新结论
            f.service.setViewSource(v -> {
                throw new IllegalStateException("库不可达");
            });
            AnalysisResult r = f.service.analyze(baseInput().build());
            assertTrue(r.dbUnavailable());
            assertTrue(r.graphStale(), "须标注图已过期");
            assertTrue(r.degraded());
            assertEquals(n, r.records().size(), "沿用上次已存记录");
            assertTrue(r.summary().contains("不推算新结论"));
            assertFalse(r.writeBackEnabled());
        }
    }

    @Test
    void dictionaryUnavailable_emptyFieldsAndError() {
        try (Fixture f = new Fixture(dir, null)) {
            injectCommon(f.service);
            f.service.setDictionarySource(path -> {
                throw new IllegalStateException("字段元数据表不可读");
            });
            AnalysisResult r = f.service.analyze(baseInput().build());
            assertFalse(r.dictionaryAvailable());
            assertTrue(r.degraded());
            assertTrue(r.records().isEmpty(), "清单字段区为空");
            assertTrue(r.summary().contains("字典不可用"));
            assertTrue(r.summary().contains("禁止按空字典"), "不得按空字典算影响面");
            assertFalse(r.writeBackEnabled());
        }
    }

    @Test
    void modelUnavailable_degradedAndWriteBackForbidden() {
        try (Fixture f = new Fixture(dir, null)) {
            injectCommon(f.service);
            injectFixedDict(f.service);
            AnalysisResult r = f.service.analyze(
                    baseInput().modelGateway(new ThrowingGateway()).build());
            assertTrue(r.modelUnavailable());
            assertTrue(r.degraded());
            assertEquals(ImpactStatus.DEGRADED, r.status(), "模型不可达 → DEGRADED");
            assertFalse(r.writeBackEnabled(), "DEGRADED 禁止回写");
            for (ImpactRecord rec : r.records()) {
                assertEquals(ImpactStatus.DEGRADED, rec.status());
            }
        }
    }

    @Test
    void parseFailure_reportsLineColumnAndReviewItems() {
        try (Fixture f = new Fixture(dir, null)) {
            injectCommon(f.service);
            injectFixedDict(f.service);
            StructuredError err = new StructuredError(3, 7, "PARSE_ERROR", "语法错误");
            ManualReviewItem review = new ManualReviewItem("V_BILL_FEE", "第 3 行第 7 列",
                    ManualReviewItem.Reason.PARSE_FAILURE, "人工核对列来源");
            f.service.setParseSource((v, sql) -> ParseResult.failure(
                    v, SqlDialect.ORACLE, "", err, review));

            AnalysisResult r = f.service.analyze(baseInput().build());
            assertTrue(r.parseFailed());
            assertTrue(r.degraded());
            assertFalse(r.reviewItems().isEmpty(), "给出待人工核对清单");
            assertTrue(r.summary().contains("第 3 行第 7 列"), "按行列号报告");
            assertTrue(r.records().isEmpty(), "解析失败不推算影响面");
        }
    }

    @Test
    void confirmThenWithdraw_stateTransitionsAndAuditTrace() {
        try (Fixture f = new Fixture(dir, null)) {
            injectCommon(f.service);
            injectFixedDict(f.service);
            AnalysisResult a = f.service.analyze(baseInput().build());
            String idem = a.records().get(0).idempotencyKey();
            assertFalse(idem.isBlank());

            AnalysisResult c = f.service.confirm(idem, "专员A");
            assertEquals(ImpactStatus.CONFIRMED, c.status());
            for (ImpactRecord rec : c.records()) {
                assertEquals(ImpactStatus.CONFIRMED, rec.status());
                assertEquals("专员A", rec.confirmedBy());
                assertFalse(rec.confirmedAt().isBlank());
                assertTrue(rec.isWriteBackEligible());
            }

            AnalysisResult w = f.service.withdraw(idem, "专员A");
            assertEquals(ImpactStatus.PENDING_CONFIRM, w.status(), "撤回状态回 PENDING_CONFIRM");
            for (ImpactRecord rec : w.records()) {
                assertEquals(ImpactStatus.PENDING_CONFIRM, rec.status());
            }
            // 只追加撤回条目，原条目仍在且不物理删除
            assertTrue(f.audit.all().stream().anyMatch(
                    e -> "撤回".equals(e.action()) && e.withdrawn()));
            assertTrue(f.audit.byView("V_BILL_FEE").size() >= 2, "原分析/确认条目仍在");
            assertTrue(f.store.find(idem).isPresent(), "记录未被物理删除");
            assertThrows(IllegalStateException.class,
                    () -> ImpactStateMachine.transition(
                            w.records().get(0), ImpactStatus.WRITTEN_BACK,
                            ImpactStateMachine.Actor.TOOL),
                    "未再次人工确认前不得直接回写");
        }
    }

    @Test
    void confirmThenWriteBack_marksWrittenBack() {
        OkTransport fake = new OkTransport();
        IfaceRegistryClient client = new IfaceRegistryClient("http://registry.local", "TOKEN", fake);
        try (Fixture f = new Fixture(dir, client)) {
            injectCommon(f.service);
            injectFixedDict(f.service);
            AnalysisResult a = f.service.analyze(
                    baseInput().permissionGranted(true).writeSwitchOn(true).build());
            assertTrue(f.service.writeBackEnabled(), "权限 + 开关开启后具备回写条件");
            String idem = a.records().get(0).idempotencyKey();

            AnalysisResult c = f.service.confirm(idem, "专员A");
            assertEquals(ImpactStatus.WRITTEN_BACK, c.status());
            for (ImpactRecord rec : c.records()) {
                assertEquals(ImpactStatus.WRITTEN_BACK, rec.status());
                assertFalse(rec.writeBackAt().isBlank());
            }
            assertEquals(1, fake.calls.size(), "确认后幂等回写发出一次请求");
            assertTrue(fake.calls.get(0).contains("field-change-impacts"));
            assertTrue(fake.calls.get(0).contains(idem), "请求带同一幂等键");
            assertTrue(f.stats.writeBackSuccess() >= 1);
        }
    }

    // ---- 测试替身 ----

    private static final class ThrowingGateway implements ModelGateway {
        @Override
        public AttributionResult attribute(AttributionRequest request) {
            throw new IllegalStateException("模型不可达");
        }

        @Override
        public String gatewayName() {
            return "throwing-fake";
        }
    }

    private static final class OkTransport implements HttpTransport {
        final List<String> calls = new ArrayList<>();

        @Override
        public RawResponse execute(String method, String url, Map<String, String> headers,
                                   String body, int timeoutSeconds) {
            calls.add(method + " " + url + " idem=" + headers.get("Idempotency-Key"));
            return new RawResponse(200, "{\"ok\":true}");
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ImpactStore store;
        final AuditLog audit;
        final RunStats stats;
        final ImpactAnalysisService service;

        Fixture(Path dir, IfaceRegistryClient client) {
            store = new ImpactStore(dir.resolve("impact.db"));
            audit = new AuditLog(dir.resolve("audit.log"));
            stats = new RunStats(dir.resolve("stats.json"));
            service = new ImpactAnalysisService(store, audit, client,
                    dir.resolve("orders"), stats, null);
        }

        @Override
        public void close() {
            store.close();
        }
    }
}
