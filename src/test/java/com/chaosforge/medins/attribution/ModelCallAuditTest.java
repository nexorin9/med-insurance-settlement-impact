package com.chaosforge.medins.attribution;

import com.chaosforge.medins.model.ChangeKind;
import com.chaosforge.medins.model.ListField;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Task 32 / ModelCallAuditTest：六类错误归一、审计字段与脱敏、重试策略（5xx≤2 / 4xx 不重试）。 */
class ModelCallAuditTest {

    private static final String API_KEY = "sk-stub-KEY-NOT-REAL-0003";
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

    private static OpenAiCompatibleGateway withExecutor(HttpExecutor exec) {
        return new OpenAiCompatibleGateway("http://stub.invalid", API_KEY, MODEL, 5, exec);
    }

    private static AttributionRequest req(String view) {
        return AttributionRequest.builder()
                .viewName(view)
                .changeKind(ChangeKind.LOGIC_CHANGED)
                .changeNoteZh("口径调整")
                .candidates(List.of(new CandidateEdge("金额合计", ListField.SECTION_FEE,
                        "fee_amt", "", 1, "词面不一致")))
                .build();
    }

    private static String badSchemaBody() {
        return LocalStubServer.openAiBody(
                "{\"affected\":[{\"verdict\":\"哈哈\"}],\"summary_zh\":\"x\"}");
    }

    @Test
    void sixErrorCodesAreMappedToFixedCodes() {
        HttpExecutor connectTimeout = s -> {
            throw new HttpConnectTimeoutException("connect timeout");
        };
        assertEquals(ModelErrorCode.MODEL_CONNECT_TIMEOUT,
                assertThrows(ModelGatewayException.class,
                        () -> withExecutor(connectTimeout).attribute(req("v1"))).code());

        HttpExecutor readTimeout = s -> {
            throw new HttpTimeoutException("read timeout");
        };
        assertEquals(ModelErrorCode.MODEL_READ_TIMEOUT,
                assertThrows(ModelGatewayException.class,
                        () -> withExecutor(readTimeout).attribute(req("v2"))).code());

        HttpExecutor fourXx = s -> new HttpResponse(401, "{}");
        assertEquals(ModelErrorCode.MODEL_HTTP_4XX,
                assertThrows(ModelGatewayException.class,
                        () -> withExecutor(fourXx).attribute(req("v3"))).code());

        HttpExecutor fiveXx = s -> new HttpResponse(503, "{}");
        assertEquals(ModelErrorCode.MODEL_HTTP_5XX,
                assertThrows(ModelGatewayException.class,
                        () -> withExecutor(fiveXx).attribute(req("v4"))).code());

        HttpExecutor empty = s -> new HttpResponse(200, "");
        assertEquals(ModelErrorCode.MODEL_EMPTY_RESPONSE,
                assertThrows(ModelGatewayException.class,
                        () -> withExecutor(empty).attribute(req("v5"))).code());

        HttpExecutor badSchema = s -> new HttpResponse(200, badSchemaBody());
        assertEquals(ModelErrorCode.MODEL_SCHEMA_INVALID,
                assertThrows(ModelGatewayException.class,
                        () -> withExecutor(badSchema).attribute(req("v6"))).code());
    }

    @Test
    void fiveXxRetriesAtMostTwiceWhileFourXxAndSchemaDoNotRetry() {
        AtomicInteger hits5xx = new AtomicInteger();
        HttpExecutor fiveXx = s -> {
            hits5xx.incrementAndGet();
            return new HttpResponse(503, "{}");
        };
        OpenAiCompatibleGateway gw5 = withExecutor(fiveXx);
        assertThrows(ModelGatewayException.class, () -> gw5.attribute(req("v5xx")));
        assertEquals(3, hits5xx.get(), "5xx 首次 + 最多 2 次退避重试");
        assertEquals(2, gw5.audit().all().get(0).retries(), "审计记录重试次数 ≤2");

        AtomicInteger hits4xx = new AtomicInteger();
        HttpExecutor fourXx = s -> {
            hits4xx.incrementAndGet();
            return new HttpResponse(400, "{}");
        };
        OpenAiCompatibleGateway gw4 = withExecutor(fourXx);
        assertThrows(ModelGatewayException.class, () -> gw4.attribute(req("v4xx")));
        assertEquals(1, hits4xx.get(), "4xx 不重试");
        assertEquals(0, gw4.audit().all().get(0).retries());

        AtomicInteger hitsSchema = new AtomicInteger();
        HttpExecutor badSchema = s -> {
            hitsSchema.incrementAndGet();
            return new HttpResponse(200, badSchemaBody());
        };
        OpenAiCompatibleGateway gwS = withExecutor(badSchema);
        assertThrows(ModelGatewayException.class, () -> gwS.attribute(req("v_schema")));
        assertEquals(1, hitsSchema.get(), "schema 不合规不重试");
        assertEquals(0, gwS.audit().all().get(0).retries());
    }

    @Test
    void auditEntryHasAllFieldsOnSuccess() throws Exception {
        HttpExecutor ok = s -> new HttpResponse(200, LocalStubServer.defaultBody());
        OpenAiCompatibleGateway gw = withExecutor(ok);

        gw.attribute(req("v_bill_fee"));

        List<AuditEntry> all = gw.audit().all();
        assertEquals(1, all.size(), "每次调用落一条审计");
        AuditEntry e = all.get(0);
        assertEquals(MODEL, e.model());
        assertFalse(e.requestDigest().isBlank());
        assertTrue(e.elapsedMs() >= 0L);
        assertEquals(0, e.retries());
        assertNull(e.errorCode());
        assertFalse(e.degraded());
        assertEquals("v_bill_fee", e.view());
        assertEquals(AttributionService.EXAMPLE_BATCH, e.batch());
        assertFalse(e.at().isBlank());
    }

    @Test
    void failedCallAuditRecordsErrorCodeAndDegraded() {
        HttpExecutor bad = s -> new HttpResponse(429, "{}");
        OpenAiCompatibleGateway gw = withExecutor(bad);

        assertThrows(ModelGatewayException.class, () -> gw.attribute(req("v_err")));

        AuditEntry e = gw.audit().all().get(0);
        assertEquals(ModelErrorCode.MODEL_HTTP_4XX, e.errorCode());
        assertTrue(e.degraded(), "失败调用审计标记为会导致降级");
        assertEquals(0, e.retries());
        assertFalse(e.requestDigest().contains(API_KEY));
    }

    @Test
    void auditCanBeQueriedByViewAndBatch() throws Exception {
        HttpExecutor ok = s -> new HttpResponse(200, LocalStubServer.defaultBody());
        OpenAiCompatibleGateway gw = withExecutor(ok);

        gw.attribute(req("v_a"));
        gw.attribute(req("v_b"));
        gw.attribute(req("v_a"));

        assertEquals(2, gw.audit().byView("v_a").size());
        assertEquals(1, gw.audit().byView("v_b").size());
        assertEquals(0, gw.audit().byView("v_missing").size());
        assertEquals(3, gw.audit().byBatch(AttributionService.EXAMPLE_BATCH).size());
        assertEquals(3, gw.audit().all().size());
    }

    @Test
    void auditTextNeverContainsCredentialLiterals() {
        HttpExecutor bad = s -> new HttpResponse(503, "{}");
        OpenAiCompatibleGateway gw = withExecutor(bad);
        assertThrows(ModelGatewayException.class, () -> gw.attribute(req("v_secret")));

        for (AuditEntry e : gw.audit().all()) {
            assertFalse(e.requestDigest().contains(API_KEY), "审计请求摘要不含凭据");
            assertFalse(e.model().contains(API_KEY));
            assertFalse(e.view().contains(API_KEY));
        }
        AuditEntry direct = new AuditEntry(MODEL, "Bearer " + API_KEY + " api_key=" + API_KEY,
                1L, 0, null, false, "v", "<EXAMPLE_BATCH>", "at");
        assertFalse(direct.requestDigest().contains(API_KEY), "Bearer / api_key= 模式须被脱敏");
    }

    @Test
    void realHttpLinkAlsoProducesAuditEntry() throws Exception {
        server.setResponder(c -> new LocalStubServer.Reply(200, LocalStubServer.defaultBody()));
        OpenAiCompatibleGateway gw =
                new OpenAiCompatibleGateway(server.baseUrl(), API_KEY, MODEL, 5);

        AttributionResult r = gw.attribute(req("v_real"));

        assertFalse(r.degraded());
        assertEquals(1, gw.audit().all().size());
        assertEquals("v_real", gw.audit().all().get(0).view());
        assertTrue(server.hits() >= 1, "默认 JDK HttpClient 真实走到本地替身");
    }
}
