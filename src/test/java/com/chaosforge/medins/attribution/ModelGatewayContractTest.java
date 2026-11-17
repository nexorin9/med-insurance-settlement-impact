package com.chaosforge.medins.attribution;

import com.chaosforge.medins.model.ChangeKind;
import com.chaosforge.medins.model.ImpactRecord;
import com.chaosforge.medins.model.ImpactStatus;
import com.chaosforge.medins.model.ListField;
import com.chaosforge.medins.model.Verdict;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 35 / ModelGatewayContractTest：正式 HTTP 协议 + 六类断言 + 服务可替换性收口。
 * 本地协议替身只在测试数据层，产品代码走真实 JDK HttpClient；替身不是最终对接面。
 */
class ModelGatewayContractTest {

    private static final String API_KEY = "sk-stub-KEY-NOT-REAL-0006";
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

    private static OpenAiCompatibleGateway gateway() {
        return new OpenAiCompatibleGateway(server.baseUrl(), API_KEY, MODEL, 5);
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

    private static String badSchemaBody() {
        return LocalStubServer.openAiBody(
                "{\"affected\":[{\"verdict\":\"哈哈\"}],\"summary_zh\":\"x\"}");
    }

    // ---------- 六类协议断言 ----------

    @Test
    void structuredOutputIsParsed() throws Exception {
        server.setResponder(c -> new LocalStubServer.Reply(200, LocalStubServer.defaultBody()));

        AttributionResult r = gateway().attribute(request());

        assertFalse(r.degraded());
        assertEquals(1, r.affected().size());
        assertEquals(Verdict.MATERIALLY_AFFECTED, r.affected().get(0).verdict());
        assertEquals("医疗总费用金额合计", r.affected().get(0).listField());
        assertEquals("fee_amt", r.affected().get(0).sourceColumn());
        assertFalse(r.summaryZh().isBlank());
        assertEquals("/v1/chat/completions", server.last().path);
    }

    @Test
    void connectTimeoutIsClassified() {
        HttpExecutor exec = s -> {
            throw new HttpConnectTimeoutException("connect timeout");
        };
        OpenAiCompatibleGateway gw =
                new OpenAiCompatibleGateway(server.baseUrl(), API_KEY, MODEL, 5, exec);

        ModelGatewayException e = assertThrows(ModelGatewayException.class, () -> gw.attribute(request()));
        assertEquals(ModelErrorCode.MODEL_CONNECT_TIMEOUT, e.code());
    }

    @Test
    void readTimeoutIsClassifiedSeparately() {
        HttpExecutor exec = s -> {
            throw new HttpTimeoutException("read timeout");
        };
        OpenAiCompatibleGateway gw =
                new OpenAiCompatibleGateway(server.baseUrl(), API_KEY, MODEL, 5, exec);

        ModelGatewayException e = assertThrows(ModelGatewayException.class, () -> gw.attribute(request()));
        assertEquals(ModelErrorCode.MODEL_READ_TIMEOUT, e.code(), "读超时与连接超时分别归类");
    }

    @Test
    void http4xxIsClassified() {
        server.setResponder(c -> new LocalStubServer.Reply(404, "{}"));

        ModelGatewayException e = assertThrows(ModelGatewayException.class,
                () -> gateway().attribute(request()));
        assertEquals(ModelErrorCode.MODEL_HTTP_4XX, e.code());
    }

    @Test
    void http5xxIsClassified() {
        server.setResponder(c -> new LocalStubServer.Reply(503, "{}"));

        ModelGatewayException e = assertThrows(ModelGatewayException.class,
                () -> gateway().attribute(request()));
        assertEquals(ModelErrorCode.MODEL_HTTP_5XX, e.code());
        assertEquals(3, server.hits(), "5xx 首次 + 最多 2 次重试");
    }

    @Test
    void emptyResponseIsClassified() {
        server.setResponder(c -> new LocalStubServer.Reply(200, ""));

        ModelGatewayException e = assertThrows(ModelGatewayException.class,
                () -> gateway().attribute(request()));
        assertEquals(ModelErrorCode.MODEL_EMPTY_RESPONSE, e.code());
    }

    @Test
    void schemaInvalidIsClassified() {
        server.setResponder(c -> new LocalStubServer.Reply(200, badSchemaBody()));

        ModelGatewayException e = assertThrows(ModelGatewayException.class,
                () -> gateway().attribute(request()));
        assertEquals(ModelErrorCode.MODEL_SCHEMA_INVALID, e.code());
    }

    // ---------- 审计元数据 + 降级后不产出可回写记录（呼应 id 32 / 33） ----------

    @Test
    void auditMetadataCompleteAndNoWriteBackAfterDegrade() {
        server.setResponder(c -> new LocalStubServer.Reply(503, "{}"));
        OpenAiCompatibleGateway gw = gateway();

        assertThrows(ModelGatewayException.class, () -> gw.attribute(request()));

        List<AuditEntry> all = gw.audit().all();
        assertEquals(1, all.size(), "每次调用落一条审计");
        AuditEntry e = all.get(0);
        assertEquals(MODEL, e.model());
        assertFalse(e.requestDigest().isBlank());
        assertTrue(e.elapsedMs() >= 0L);
        assertEquals(2, e.retries());
        assertEquals(ModelErrorCode.MODEL_HTTP_5XX, e.errorCode());
        assertTrue(e.degraded());
        assertEquals("v_bill_fee", e.view());
        assertEquals(AttributionService.EXAMPLE_BATCH, e.batch());
        assertFalse(e.at().isBlank());
        assertFalse(e.requestDigest().contains(API_KEY), "审计文本不含凭据");

        AttributionResult degradedResult = AttributionResult.degraded("归因未生成");
        List<ImpactRecord> degradedRecords = AttributionService.degrade(List.of(
                ImpactRecord.builder()
                        .viewName("v_bill_fee")
                        .changedField("fee_amt")
                        .status(ImpactStatus.ATTRIBUTED)
                        .build()));
        assertFalse(AttributionService.writeBackEnabled(degradedResult));
        assertTrue(degradedRecords.stream().noneMatch(ImpactRecord::isWriteBackEligible),
                "降级后不产出可回写记录");
    }

    // ---------- 服务可替换性 ----------

    @Test
    void businessLayerOnlyReferencesModelGatewayInterface() {
        assertTrue(ModelGateway.class.isInterface());
        StringBuilder signatures = new StringBuilder();
        for (Method m : AttributionService.class.getDeclaredMethods()) {
            signatures.append(m.toGenericString()).append('\n');
        }
        assertFalse(signatures.toString().contains("OpenAiCompatibleGateway"),
                "业务层方法签名不得引用具体网关实现");
        assertTrue(signatures.toString().contains("ModelGateway"),
                "业务层只依赖 ModelGateway 接口");
    }

    @Test
    void switchingBaseUrlToSameStubDoesNotChangeBusinessCode() throws Exception {
        // 两条不同 base_url 指向同一本地替身；业务代码（AttributionService.attribute）只以 ModelGateway 为参数。
        OpenAiCompatibleGateway gw1 =
                new OpenAiCompatibleGateway(server.baseUrl(), API_KEY, MODEL, 5);
        OpenAiCompatibleGateway gw2 =
                new OpenAiCompatibleGateway(server.baseUrl("/alt"), API_KEY, MODEL, 5);

        AttributionResult r1 = AttributionService.attribute(gw1, request());
        AttributionResult r2 = AttributionService.attribute(gw2, request());

        assertFalse(r1.degraded());
        assertFalse(r2.degraded());
        assertEquals(r1.affected().size(), r2.affected().size());
        assertEquals(r1.affected().get(0).verdict(), r2.affected().get(0).verdict());
        assertEquals(r1.summaryZh(), r2.summaryZh());
        assertEquals("openai-compatible", gw1.gatewayName());
        assertEquals("openai-compatible", gw2.gatewayName());

        List<String> paths = server.captured().stream().map(c -> c.path).toList();
        assertTrue(paths.stream().anyMatch(p -> p.equals("/v1/chat/completions")));
        assertTrue(paths.stream().anyMatch(p -> p.equals("/alt/v1/chat/completions")),
                "换 base_url 后仍走正式协议路径，业务代码不变");
    }
}
