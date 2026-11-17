package com.chaosforge.medins.attribution;

import com.chaosforge.medins.model.ChangeKind;
import com.chaosforge.medins.model.ImpactRecord;
import com.chaosforge.medins.model.ImpactScope;
import com.chaosforge.medins.model.ImpactStatus;
import com.chaosforge.medins.model.ImpactType;
import com.chaosforge.medins.model.ListField;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Task 33 / AttributionDegradationTest：降级不产出可回写记录、服务层拒绝回写、状态不被模型推进。 */
class AttributionDegradationTest {

    private static final String API_KEY = "sk-stub-KEY-NOT-REAL-0004";
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

    private static ImpactRecord record(String field, ImpactStatus status) {
        return ImpactRecord.builder()
                .viewName("v_bill_fee")
                .changedField("fee_amt")
                .changeKind(ChangeKind.LOGIC_CHANGED)
                .section(ListField.SECTION_FEE)
                .listFieldName(field)
                .sourceColumn("fee_amt")
                .impact(ImpactType.DIRECT)
                .confidence(0.9)
                .scope(ImpactScope.STRICT)
                .batch(AttributionService.EXAMPLE_BATCH)
                .verdict(com.chaosforge.medins.model.Verdict.NOT_ATTRIBUTED)
                .status(status)
                .build();
    }

    private static AttributionRequest request() {
        return AttributionRequest.builder()
                .viewName("v_bill_fee")
                .changeKind(ChangeKind.LOGIC_CHANGED)
                .changeNoteZh("口径调整")
                .candidates(List.of(new CandidateEdge("金额合计", ListField.SECTION_FEE,
                        "fee_amt", "SUM(x)", 2, "词面不一致")))
                .build();
    }

    private static AttributionResult goodResult() {
        return AttributionResult.of(List.of(new Affected("金额合计", ListField.SECTION_FEE,
                "fee_amt", com.chaosforge.medins.model.Verdict.MATERIALLY_AFFECTED, 0.9, "理由")), "正常摘要");
    }

    @Test
    void degradeProducesNoWriteBackEligibleRecords() {
        List<ImpactRecord> input = List.of(
                record("金额合计", ImpactStatus.ATTRIBUTED),
                record("甲类费用", ImpactStatus.PENDING_CONFIRM));

        List<ImpactRecord> degraded = AttributionService.degrade(input);

        assertEquals(2, degraded.size());
        for (ImpactRecord r : degraded) {
            assertEquals(ImpactStatus.DEGRADED, r.status());
            assertFalse(r.isWriteBackEligible(), "降级后不得产出可回写记录");
        }
    }

    @Test
    void serviceRejectsWriteBackWhenDegraded() {
        AttributionResult degradedResult = AttributionResult.degraded("模型不可达，归因未生成");
        assertTrue(degradedResult.degraded());
        assertFalse(AttributionService.writeBackEnabled(degradedResult), "降级显式关闭回写");

        List<ImpactRecord> degradedRecords =
                AttributionService.degrade(List.of(record("金额合计", ImpactStatus.ATTRIBUTED)));
        assertFalse(AttributionService.writeBackEnabled(degradedRecords, degradedResult),
                "服务层拒绝回写请求");
        assertFalse(AttributionService.writeBackEnabled(degradedRecords, goodResult()),
                "含 DEGRADED 记录时即便结果正常也拒绝回写");
    }

    @Test
    void modelDoesNotAdvanceConfirmedOrWrittenBackStatus() {
        ImpactRecord confirmed = record("甲类费用", ImpactStatus.CONFIRMED);
        ImpactRecord written = record("乙类费用", ImpactStatus.WRITTEN_BACK);
        ImpactRecord attributed = record("金额合计", ImpactStatus.ATTRIBUTED);
        List<ImpactRecord> before = List.of(confirmed, written, attributed);

        // 模型不可达（5xx）触发降级路径。
        server.setResponder(c -> new LocalStubServer.Reply(503, "{}"));
        OpenAiCompatibleGateway gw =
                new OpenAiCompatibleGateway(server.baseUrl(), API_KEY, MODEL, 5);
        AttributionResult result;
        try {
            result = gw.attribute(request());
        } catch (ModelGatewayException e) {
            result = AttributionResult.degraded("模型不可达，归因未生成：" + e.code());
        }
        assertTrue(result.degraded());

        // 模型调用本身不接触影响面记录：状态前后均未变化。
        assertEquals(ImpactStatus.CONFIRMED, before.get(0).status());
        assertEquals(ImpactStatus.WRITTEN_BACK, before.get(1).status());
        assertEquals(ImpactStatus.ATTRIBUTED, before.get(2).status());

        List<ImpactRecord> after = AttributionService.degrade(before);
        assertSame(confirmed, after.get(0), "CONFIRMED 记录模型无权改动");
        assertSame(written, after.get(1), "WRITTEN_BACK 记录模型无权改动");
        assertEquals(ImpactStatus.CONFIRMED, after.get(0).status(), "CONFIRMED 状态未变化");
        assertEquals(ImpactStatus.WRITTEN_BACK, after.get(1).status(), "WRITTEN_BACK 状态未变化");
        assertEquals(ImpactStatus.DEGRADED, after.get(2).status(), "仅非终态记录降级");
    }

    @Test
    void degradedMarkIsVisibleInRecordsAndVendorNote() {
        List<ImpactRecord> degraded =
                AttributionService.degrade(List.of(record("金额合计", ImpactStatus.ATTRIBUTED)));

        for (ImpactRecord r : degraded) {
            assertTrue(r.reasonZh().contains(AttributionService.DEGRADED_MARK),
                    "记录上须显著标注「归因未生成」");
        }
        String note = AttributionService.draftVendorNote(degraded, "v_bill_fee", "fee_amt");
        assertTrue(note.contains(AttributionService.DEGRADED_MARK), "说明文本同步带出降级标注");
    }

    @Test
    void degradedResultIsNotMergedIntoRecords() {
        ImpactRecord r = record("金额合计", ImpactStatus.ATTRIBUTED);
        AttributionResult degraded = AttributionResult.degraded("归因未生成");

        List<ImpactRecord> merged = AttributionService.merge(List.of(r), degraded);

        assertSame(r, merged.get(0), "降级结果不得覆盖影响面判定");
        assertEquals(com.chaosforge.medins.model.Verdict.NOT_ATTRIBUTED, merged.get(0).verdict());
    }
}
