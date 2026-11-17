package com.chaosforge.medins.attribution;

import com.chaosforge.medins.model.ChangeKind;
import com.chaosforge.medins.model.ListField;
import com.chaosforge.medins.model.Verdict;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Task 31 / AttributionContractTest：prompt 契约、严格 JSON schema 校验、确定性基线不调模型。 */
class AttributionContractTest {

    private static final String API_KEY = "sk-stub-KEY-NOT-REAL-0002";
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

    private static AttributionRequest sampleRequest() {
        return AttributionRequest.builder()
                .viewName("v_bill_fee")
                .changeKind(ChangeKind.LOGIC_CHANGED)
                .changeNoteZh("费用列口径调整")
                .candidates(List.of(new CandidateEdge("医疗总费用金额合计", ListField.SECTION_FEE,
                        "fee_amt", "SUM(l_extendedprice)", 2, "聚合来源，词面不一致")))
                .build();
    }

    /** 单条合法 affected + summary_zh 的可变节点，供各失败用例改造。 */
    private static ObjectNode contentNode() {
        ObjectMapper m = new ObjectMapper();
        ObjectNode content = m.createObjectNode();
        ArrayNode arr = content.putArray("affected");
        ObjectNode a = arr.addObject();
        a.put(AttributionSchema.F_LIST_FIELD, "金额合计");
        a.put(AttributionSchema.F_SECTION, "医疗收费信息");
        a.put(AttributionSchema.F_SOURCE_COLUMN, "fee_amt");
        a.put(AttributionSchema.F_VERDICT, AttributionSchema.VERDICT_MATERIAL);
        a.put(AttributionSchema.F_CONFIDENCE, 0.8);
        a.put(AttributionSchema.F_REASON_ZH, "理由");
        content.put(AttributionSchema.F_SUMMARY_ZH, "摘要");
        return content;
    }

    private static ObjectNode firstAffected(ObjectNode content) {
        return (ObjectNode) content.path(AttributionSchema.F_AFFECTED).get(0);
    }

    private static String json(ObjectNode node) throws Exception {
        return new ObjectMapper().writeValueAsString(node);
    }

    // ---------- 输入契约 ----------

    @Test
    void promptBuildCarriesStructureAndNoPatientDetails() throws Exception {
        Map<String, Object> built = AttributionPrompt.build(sampleRequest());
        assertEquals("v_bill_fee", built.get("view_name"));
        assertEquals("logic_changed", built.get("change_kind"));
        assertEquals("费用列口径调整", built.get("change_note_zh"));
        assertTrue(built.containsKey("candidates"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) built.get("candidates");
        assertEquals(1, candidates.size());
        Map<String, Object> c = candidates.get(0);
        for (String key : List.of("list_field", "section", "source_column", "expression", "hops", "reason_hint")) {
            assertTrue(c.containsKey(key), "候选边须含结构字段 " + key);
        }
        String serialized = new ObjectMapper().writeValueAsString(built);
        assertFalse(serialized.contains("患者姓名"), "不得含患者可识别明细");
        assertFalse(serialized.contains("身份证"), "不得含患者可识别明细");
        assertFalse(c.containsKey("patient"), "不得含患者明细键");
    }

    @Test
    void systemPromptDemandsStrictJsonAndFourVerdicts() {
        String sp = AttributionPrompt.systemPrompt();
        for (String key : List.of("affected", "list_field", "section", "source_column", "verdict",
                "confidence", "reason_zh", "summary_zh", "严格 JSON", "0 到 1",
                AttributionSchema.VERDICT_MATERIAL, AttributionSchema.VERDICT_SAME_NAME,
                AttributionSchema.VERDICT_SUSPECTED, AttributionSchema.VERDICT_UNDETERMINABLE)) {
            assertTrue(sp.contains(key), "systemPrompt 须含 " + key);
        }
    }

    // ---------- 严格 schema ----------

    @Test
    void validResponseParsedThroughRealGateway() throws Exception {
        server.setResponder(c -> new LocalStubServer.Reply(200, LocalStubServer.defaultBody()));
        OpenAiCompatibleGateway gw =
                new OpenAiCompatibleGateway(server.baseUrl(), API_KEY, MODEL, 5);

        AttributionResult r = gw.attribute(sampleRequest());

        assertFalse(r.degraded());
        assertEquals(1, r.affected().size());
        assertEquals(Verdict.MATERIALLY_AFFECTED, r.affected().get(0).verdict());
        assertEquals("fee_amt", r.affected().get(0).sourceColumn());
    }

    @Test
    void allFourChineseVerdictsMapToEnum() throws Exception {
        ObjectMapper m = new ObjectMapper();
        ObjectNode content = m.createObjectNode();
        ArrayNode arr = content.putArray("affected");
        String[] verdicts = {
                AttributionSchema.VERDICT_MATERIAL,
                AttributionSchema.VERDICT_SAME_NAME,
                AttributionSchema.VERDICT_SUSPECTED,
                AttributionSchema.VERDICT_UNDETERMINABLE
        };
        for (String v : verdicts) {
            ObjectNode a = arr.addObject();
            a.put("list_field", "字段");
            a.put("section", "分区");
            a.put("source_column", "col");
            a.put("verdict", v);
            a.put("confidence", 0.5);
            a.put("reason_zh", "理由");
        }
        content.put("summary_zh", "四档齐全");

        AttributionResult r = AttributionResult.fromJson(json(content));

        assertEquals(4, r.affected().size());
        assertEquals(Verdict.MATERIALLY_AFFECTED, r.affected().get(0).verdict());
        assertEquals(Verdict.SAME_NAME_DIFFERENT_MEANING, r.affected().get(1).verdict());
        assertEquals(Verdict.SUSPECTED, r.affected().get(2).verdict());
        assertEquals(Verdict.UNDETERMINABLE, r.affected().get(3).verdict());
        assertEquals(0.5d, r.affected().get(0).confidence(), 1e-9);
        assertEquals("理由", r.affected().get(0).reasonZh());
        assertEquals("四档齐全", r.summaryZh());
        assertFalse(r.degraded());
    }

    @Test
    void illegalVerdictIsRejected() throws Exception {
        ObjectNode node = contentNode();
        firstAffected(node).put(AttributionSchema.F_VERDICT, "哈哈");
        ModelGatewayException e = assertThrows(ModelGatewayException.class,
                () -> AttributionResult.fromJson(json(node)));
        assertEquals(ModelErrorCode.MODEL_SCHEMA_INVALID, e.code());

        ObjectNode node2 = contentNode();
        firstAffected(node2).put(AttributionSchema.F_VERDICT, "未归因");
        assertThrows(ModelGatewayException.class, () -> AttributionResult.fromJson(json(node2)));
    }

    @Test
    void outOfRangeConfidenceIsRejected() throws Exception {
        ObjectNode high = contentNode();
        firstAffected(high).put(AttributionSchema.F_CONFIDENCE, 1.5);
        assertEquals(ModelErrorCode.MODEL_SCHEMA_INVALID,
                assertThrows(ModelGatewayException.class,
                        () -> AttributionResult.fromJson(json(high))).code());

        ObjectNode low = contentNode();
        firstAffected(low).put(AttributionSchema.F_CONFIDENCE, -0.1);
        assertThrows(ModelGatewayException.class, () -> AttributionResult.fromJson(json(low)));
    }

    @Test
    void missingFieldIsRejected() throws Exception {
        ObjectNode noListField = contentNode();
        firstAffected(noListField).remove(AttributionSchema.F_LIST_FIELD);
        assertEquals(ModelErrorCode.MODEL_SCHEMA_INVALID,
                assertThrows(ModelGatewayException.class,
                        () -> AttributionResult.fromJson(json(noListField))).code());

        ObjectNode noSummary = contentNode();
        noSummary.remove(AttributionSchema.F_SUMMARY_ZH);
        assertThrows(ModelGatewayException.class, () -> AttributionResult.fromJson(json(noSummary)));

        ObjectNode stringConfidence = contentNode();
        firstAffected(stringConfidence).put(AttributionSchema.F_CONFIDENCE, "0.8");
        assertEquals(ModelErrorCode.MODEL_SCHEMA_INVALID,
                assertThrows(ModelGatewayException.class,
                        () -> AttributionResult.fromJson(json(stringConfidence))).code());
    }

    @Test
    void nonJsonIsRejected() {
        ModelGatewayException e = assertThrows(ModelGatewayException.class,
                () -> AttributionResult.fromJson("not-a-json"));
        assertEquals(ModelErrorCode.MODEL_SCHEMA_INVALID, e.code());
        assertThrows(ModelGatewayException.class, () -> AttributionResult.fromJson(""));
    }

    // ---------- 确定性基线 ----------

    @Test
    void exactColumnMatchDoesNotInvokeModel() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ModelGateway counting = new ModelGateway() {
            @Override
            public AttributionResult attribute(AttributionRequest r) {
                calls.incrementAndGet();
                return AttributionResult.of(List.of(), "不该被调用");
            }

            @Override
            public String gatewayName() {
                return "counting-stub";
            }
        };
        AttributionRequest req = AttributionRequest.builder()
                .viewName("v_bill_fee")
                .changeKind(ChangeKind.RENAMED)
                .changeNoteZh("列改名")
                .candidates(List.of(
                        CandidateEdge.exact("金额合计", ListField.SECTION_FEE, "金额合计", "", 1, "词面一致"),
                        CandidateEdge.exact("姓名", ListField.SECTION_BASIC, "姓名", "", 1, "词面一致")))
                .build();

        AttributionResult r = AttributionService.attribute(counting, req);

        assertEquals(0, calls.get(), "全部列名精确匹配时不得触发模型调用");
        assertEquals(2, r.affected().size());
        for (Affected a : r.affected()) {
            assertEquals(Verdict.MATERIALLY_AFFECTED, a.verdict());
            assertEquals(1.0d, a.confidence(), 1e-9);
            assertTrue(a.reasonZh().contains("未调用模型"));
        }
    }

    @Test
    void modelOnlyReceivesNonExactCandidates() throws Exception {
        AtomicReference<AttributionRequest> seen = new AtomicReference<>();
        ModelGateway gw = new ModelGateway() {
            @Override
            public AttributionResult attribute(AttributionRequest r) {
                seen.set(r);
                return AttributionResult.of(List.of(new Affected("金额合计", ListField.SECTION_FEE,
                        "fee_amt", Verdict.MATERIALLY_AFFECTED, 0.9, "模型判定")), "模型摘要");
            }

            @Override
            public String gatewayName() {
                return "stub";
            }
        };
        AttributionRequest req = AttributionRequest.builder()
                .viewName("v_bill_fee")
                .changeKind(ChangeKind.LOGIC_CHANGED)
                .changeNoteZh("口径调整")
                .candidates(List.of(
                        CandidateEdge.exact("姓名", ListField.SECTION_BASIC, "姓名", "", 1, "精确"),
                        new CandidateEdge("金额合计", ListField.SECTION_FEE, "fee_amt", "SUM(x)", 2, "词面不一致")))
                .build();

        AttributionResult r = AttributionService.attribute(gw, req);

        assertNotNull(seen.get(), "存在词面不一致候选边时须调用模型");
        assertEquals(1, seen.get().candidates().size(), "模型只收到词面不一致的候选边");
        assertEquals("fee_amt", seen.get().candidates().get(0).sourceColumn());
        assertEquals(2, r.affected().size(), "确定性结论与模型结论并存");
    }
}
