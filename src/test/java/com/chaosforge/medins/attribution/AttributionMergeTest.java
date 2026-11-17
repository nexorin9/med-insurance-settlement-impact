package com.chaosforge.medins.attribution;

import com.chaosforge.medins.model.ChangeKind;
import com.chaosforge.medins.model.ImpactRecord;
import com.chaosforge.medins.model.ImpactScope;
import com.chaosforge.medins.model.ImpactStatus;
import com.chaosforge.medins.model.ImpactType;
import com.chaosforge.medins.model.ListField;
import com.chaosforge.medins.model.Verdict;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Task 34 / AttributionMergeTest：四档归位、不可判定进人工裁决、厂商说明草稿完整且未确认。 */
class AttributionMergeTest {

    private static final String API_KEY = "sk-stub-KEY-NOT-REAL-0005";
    private static final String MODEL = "hospital-llm-stub";

    private static LocalStubServer server;

    @BeforeAll
    static void startServer() throws Exception {
        server = new LocalStubServer();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @BeforeEach
    void resetStub() {
        server.reset();
        server.clearCaptured();
    }

    private static ImpactRecord record(String field, String sourceColumn, ImpactStatus status) {
        return ImpactRecord.builder()
                .viewName("v_bill_fee")
                .changedField("fee_amt")
                .changeKind(ChangeKind.LOGIC_CHANGED)
                .section(ListField.SECTION_FEE)
                .listFieldName(field)
                .sourceColumn(sourceColumn)
                .impact(ImpactType.DIRECT)
                .confidence(0.9)
                .scope(ImpactScope.STRICT)
                .batch(AttributionService.EXAMPLE_BATCH)
                .status(status)
                .build();
    }

    private static Affected affected(String field, String sourceColumn, Verdict verdict,
                                     double confidence, String reason) {
        return new Affected(field, ListField.SECTION_FEE, sourceColumn, verdict, confidence, reason);
    }

    private static ImpactRecord find(List<ImpactRecord> records, String field) {
        return records.stream().filter(r -> r.listFieldName().equals(field)).findFirst().orElseThrow();
    }

    private static AttributionResult fourVerdictResult() {
        return AttributionResult.of(List.of(
                affected("金额合计", "fee_amt", Verdict.MATERIALLY_AFFECTED, 0.95, "理由一"),
                affected("姓名", "name_col", Verdict.SAME_NAME_DIFFERENT_MEANING, 0.5, "理由二"),
                affected("年龄", "age_col", Verdict.SUSPECTED, 0.4, "理由三"),
                affected("病案号", "mr_col", Verdict.UNDETERMINABLE, 0.2, "理由四")), "四档摘要");
    }

    private static List<ImpactRecord> fourRecords() {
        return List.of(
                record("金额合计", "fee_amt", ImpactStatus.ATTRIBUTED),
                record("姓名", "name_col", ImpactStatus.ATTRIBUTED),
                record("年龄", "age_col", ImpactStatus.ATTRIBUTED),
                record("病案号", "mr_col", ImpactStatus.ATTRIBUTED));
    }

    private static AttributionRequest request() {
        return AttributionRequest.builder()
                .viewName("v_bill_fee")
                .changeKind(ChangeKind.LOGIC_CHANGED)
                .changeNoteZh("口径调整")
                .candidates(List.of(new CandidateEdge("金额合计", ListField.SECTION_FEE,
                        "fee_amt", "", 1, "词面不一致")))
                .build();
    }

    @Test
    void fourVerdictsAreMappedToRecordsCorrectly() {
        List<ImpactRecord> merged = AttributionService.merge(fourRecords(), fourVerdictResult());
        assertEquals(4, merged.size());

        ImpactRecord material = find(merged, "金额合计");
        assertEquals(Verdict.MATERIALLY_AFFECTED, material.verdict());
        assertEquals(ImpactType.DIRECT, material.impact());
        assertEquals(0.95d, material.confidence(), 1e-9);
        assertEquals("理由一", material.reasonZh());
        assertEquals(ImpactStatus.ATTRIBUTED, material.status());

        ImpactRecord sameName = find(merged, "姓名");
        assertEquals(Verdict.SAME_NAME_DIFFERENT_MEANING, sameName.verdict());
        assertEquals(ImpactType.SUSPECTED, sameName.impact(), "同名不同义不计入实质受影响");
        assertEquals(ImpactStatus.AMBIGUOUS, sameName.status());

        ImpactRecord suspected = find(merged, "年龄");
        assertEquals(Verdict.SUSPECTED, suspected.verdict());
        assertEquals(ImpactType.SUSPECTED, suspected.impact());
        assertEquals(ImpactStatus.ATTRIBUTED, suspected.status());

        ImpactRecord undetermined = find(merged, "病案号");
        assertEquals(Verdict.UNDETERMINABLE, undetermined.verdict());
        assertEquals(ImpactType.SUSPECTED, undetermined.impact(), "不可判定不计入实质受影响");
        assertEquals(ImpactStatus.AMBIGUOUS, undetermined.status());
    }

    @Test
    void undeterminableGoesToHumanArbitrationWithReason() {
        List<ImpactRecord> merged = AttributionService.merge(fourRecords(), fourVerdictResult());

        List<ImpactRecord> arbitration = AttributionService.humanArbitration(merged);
        ImpactRecord undetermined = arbitration.stream()
                .filter(r -> r.verdict() == Verdict.UNDETERMINABLE)
                .findFirst().orElseThrow();
        assertEquals("病案号", undetermined.listFieldName());
        assertEquals("理由四", undetermined.reasonZh(), "人工裁决清单须附 reason_zh");
        assertTrue(arbitration.stream().anyMatch(r -> r.verdict() == Verdict.SAME_NAME_DIFFERENT_MEANING));
        assertTrue(arbitration.stream().noneMatch(r -> r.verdict() == Verdict.MATERIALLY_AFFECTED));
    }

    @Test
    void mergeDoesNotAdvanceOrRevertHumanConfirmedStatus() {
        ImpactRecord confirmed = record("金额合计", "fee_amt", ImpactStatus.ATTRIBUTED)
                .toBuilder()
                .confirmedBy("示例专员")
                .confirmedAt("示例时间")
                .status(ImpactStatus.CONFIRMED)
                .build();
        AttributionResult result = AttributionResult.of(List.of(
                affected("金额合计", "fee_amt", Verdict.UNDETERMINABLE, 0.3, "模型存疑")), "摘要");

        List<ImpactRecord> merged = AttributionService.merge(List.of(confirmed), result);

        assertEquals(ImpactStatus.CONFIRMED, merged.get(0).status(), "模型不得推进/倒退人工已确认状态");
        assertEquals(Verdict.UNDETERMINABLE, merged.get(0).verdict(), "归因信息仍覆盖到记录");
        assertEquals("模型存疑", merged.get(0).reasonZh());
    }

    @Test
    void draftVendorNoteIsCompleteUnconfirmedAndUsesExampleBatch() {
        List<ImpactRecord> merged = AttributionService.merge(fourRecords(), fourVerdictResult());
        String note = AttributionService.draftVendorNote(merged, "v_bill_fee", "fee_amt");

        for (String key : List.of("改动点", "视图", "改动列", "改动类型", "改动说明",
                "受影响清单字段", "判定", "置信度", "口径", "逐条理由", "需人工裁决",
                "理由一", "理由四", AttributionService.EXAMPLE_BATCH,
                "未确认草稿", "不得自动进入回写请求体")) {
            assertTrue(note.contains(key), "说明草稿须含 " + key);
        }
        assertTrue(note.contains("<EXAMPLE_BATCH>"), "批次一律占位");
    }

    @Test
    void gatewayResultCanBeMergedIntoRecords() throws Exception {
        server.setResponder(c -> new LocalStubServer.Reply(200, LocalStubServer.defaultBody()));
        OpenAiCompatibleGateway gw =
                new OpenAiCompatibleGateway(server.baseUrl(), API_KEY, MODEL, 5);

        AttributionResult result = gw.attribute(request());
        List<ImpactRecord> merged = AttributionService.merge(
                List.of(record("医疗总费用金额合计", "fee_amt", ImpactStatus.ATTRIBUTED)), result);

        assertFalse(result.degraded());
        assertEquals(Verdict.MATERIALLY_AFFECTED, merged.get(0).verdict());
        assertEquals(ImpactStatus.ATTRIBUTED, merged.get(0).status());
    }
}
