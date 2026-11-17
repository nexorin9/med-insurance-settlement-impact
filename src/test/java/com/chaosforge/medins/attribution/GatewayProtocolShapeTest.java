package com.chaosforge.medins.attribution;

import com.chaosforge.medins.config.AppConfig;
import com.chaosforge.medins.config.ConfigException;
import com.chaosforge.medins.config.EnvSource;
import com.chaosforge.medins.model.ChangeKind;
import com.chaosforge.medins.model.ListField;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Task 30 / GatewayProtocolShapeTest：OpenAI 兼容协议形状、配置来源、凭据不进日志与审计。 */
class GatewayProtocolShapeTest {

    private static final String API_KEY = "sk-stub-KEY-NOT-REAL-0001";
    private static final String MODEL = "hospital-llm-stub";
    private static final int TIMEOUT = 7;

    private static LocalStubServer server;
    private static OpenAiCompatibleGateway gateway;

    @BeforeAll
    static void startServer() throws Exception {
        server = new LocalStubServer();
        gateway = new OpenAiCompatibleGateway(server.baseUrl(), API_KEY, MODEL, TIMEOUT);
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

    @Test
    void requestShapeMatchesOpenAiCompatibleProtocol() throws Exception {
        AttributionResult result = gateway.attribute(sampleRequest());
        assertFalse(result.degraded());

        LocalStubServer.Captured c = server.last();
        assertNotNull(c, "替身须收到一次请求");
        assertEquals("POST", c.method);
        assertEquals("/v1/chat/completions", c.path, "路径 = {baseUrl}/v1/chat/completions");
        assertEquals("application/json", c.headers.get("content-type"));
        assertEquals("Bearer " + API_KEY, c.headers.get("authorization"));

        JsonNode body = new ObjectMapper().readTree(c.body);
        assertEquals(MODEL, body.path("model").asText(), "模型名取自配置");
        JsonNode messages = body.path("messages");
        assertTrue(messages.isArray());
        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).path("role").asText());
        assertTrue(messages.get(0).path("content").asText().contains("affected"));
        assertEquals("user", messages.get(1).path("role").asText());
        assertTrue(body.has("temperature"));
        assertEquals("json_object", body.path("response_format").path("type").asText(),
                "结构化输出开关");
    }

    @Test
    void modelNameAndTimeoutComeFromConfig() throws Exception {
        AtomicReference<HttpRequestSpec> capturedSpec = new AtomicReference<>();
        HttpExecutor exec = spec -> {
            capturedSpec.set(spec);
            return new HttpResponse(200, LocalStubServer.defaultBody());
        };
        OpenAiCompatibleGateway gw =
                new OpenAiCompatibleGateway("http://stub.invalid", API_KEY, MODEL, 45, exec);

        gw.attribute(sampleRequest());

        HttpRequestSpec spec = capturedSpec.get();
        assertNotNull(spec);
        assertEquals(45, spec.timeoutSeconds(), "超时取自构造参数/环境变量");
        JsonNode body = new ObjectMapper().readTree(spec.body());
        assertEquals(MODEL, body.path("model").asText());
    }

    @Test
    void credentialsNeverAppearInExceptionAndAuditText() {
        server.setResponder(c -> new LocalStubServer.Reply(503, "{}"));

        ModelGatewayException ex = assertThrows(ModelGatewayException.class,
                () -> gateway.attribute(sampleRequest()));
        assertFalse(ex.getMessage().contains(API_KEY), "异常文本不得出现凭据字面量");
        for (AuditEntry e : gateway.audit().all()) {
            assertFalse(e.requestDigest().contains(API_KEY), "审计文本不得出现凭据字面量");
        }

        // 直接验证 Bearer 模式被脱敏器抹掉。
        AuditEntry direct = new AuditEntry(MODEL, "Authorization: Bearer " + API_KEY,
                1L, 0, null, false, "v", "<EXAMPLE_BATCH>", "at");
        assertFalse(direct.requestDigest().contains(API_KEY));
        assertTrue(direct.requestDigest().contains("***"));
    }

    @Test
    void missingConfigFailsLoudlyWithoutEchoingSecrets() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> new OpenAiCompatibleGateway("  ", API_KEY, MODEL, 5))
                .getMessage().contains("MODEL_BASE_URL"));
        assertThrows(IllegalArgumentException.class,
                () -> new OpenAiCompatibleGateway("http://x", "  ", MODEL, 5));
        assertThrows(IllegalArgumentException.class,
                () -> new OpenAiCompatibleGateway("http://x", API_KEY, "  ", 5));
        assertThrows(IllegalArgumentException.class,
                () -> new OpenAiCompatibleGateway("http://x", API_KEY, MODEL, 0));

        ConfigException missing = assertThrows(ConfigException.class,
                () -> OpenAiCompatibleGateway.fromEnv(EnvSource.of(new HashMap<>())));
        assertTrue(missing.getMessage().contains("MODEL_BASE_URL"), "缺失即显式报错");

        Map<String, String> env = new HashMap<>();
        env.put(AppConfig.ENV_MODEL_BASE_URL, "http://x");
        env.put(AppConfig.ENV_MODEL_API_KEY, API_KEY);
        // 故意缺 MODEL_NAME，验证错误信息只报变量名、不回显凭据值。
        ConfigException missingName = assertThrows(ConfigException.class,
                () -> OpenAiCompatibleGateway.fromEnv(EnvSource.of(env)));
        assertTrue(missingName.getMessage().contains("MODEL_NAME"));
        assertFalse(missingName.getMessage().contains(API_KEY), "配置异常只报变量名，不回显凭据值");
    }
}
