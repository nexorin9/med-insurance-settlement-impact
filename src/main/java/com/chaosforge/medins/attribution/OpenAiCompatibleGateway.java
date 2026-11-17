package com.chaosforge.medins.attribution;

import com.chaosforge.medins.config.AppConfig;
import com.chaosforge.medins.config.ConfigException;
import com.chaosforge.medins.config.EnvSource;
import com.chaosforge.medins.config.SecretRedactor;
import com.chaosforge.medins.model.ChangeKind;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible 模型网关 —— 单一 provider-neutral 协议实现。
 *
 * <p>协议面：{@code POST {baseUrl}/v1/chat/completions}、{@code Authorization: Bearer <apiKey>}、
 * 请求体含 model / messages / temperature / response_format({"type":"json_object"})，
 * 响应按 OpenAI 兼容格式解析 {@code choices[0].message.content}。
 *
 * <p>六类错误归一、仅超时与 5xx 有限退避重试（≤2 次）、每次调用落脱敏审计。
 * 凭据（apiKey）只出现在 Authorization 头，绝不进入异常消息、请求摘要与审计文本
 * （统一经 {@link SecretRedactor} 处理）。默认传输用 JDK HttpClient，产品代码走真实 HTTP；
 * 测试可注入 {@link HttpExecutor} 协议替身，产品代码不依赖替身。
 */
public final class OpenAiCompatibleGateway implements ModelGateway {

    /** 最大重试次数（仅超时与 5xx）。 */
    public static final int MAX_RETRIES = 2;

    private static final long BACKOFF_BASE_MS = 10L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int timeoutSeconds;
    private final HttpExecutor executor;
    private final SecretRedactor redactor;
    private final ModelCallAudit audit;

    public OpenAiCompatibleGateway(String baseUrl, String apiKey, String model, int timeoutSeconds) {
        this(baseUrl, apiKey, model, timeoutSeconds, new JdkHttpExecutor());
    }

    public OpenAiCompatibleGateway(String baseUrl, String apiKey, String model, int timeoutSeconds,
                                   HttpExecutor executor) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("缺少模型端点 baseUrl（配置项 MODEL_BASE_URL）");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("缺少模型 API Key（配置项 MODEL_API_KEY），错误信息不含取值");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("缺少模型名（配置项 MODEL_NAME）");
        }
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("MODEL_TIMEOUT_SECONDS 必须大于 0");
        }
        if (executor == null) {
            throw new IllegalArgumentException("HttpExecutor 不可为 null");
        }
        this.baseUrl = baseUrl.trim();
        this.apiKey = apiKey.trim();
        this.model = model.trim();
        this.timeoutSeconds = timeoutSeconds;
        this.executor = executor;
        this.redactor = SecretRedactor.of(List.of(this.apiKey));
        this.audit = new ModelCallAudit();
    }

    /**
     * 从环境变量构造：MODEL_BASE_URL / MODEL_API_KEY / MODEL_NAME / MODEL_TIMEOUT_SECONDS
     * 四项全部要求显式设置，缺失即显式报错（只报变量名），不静默填默认值。
     */
    public static OpenAiCompatibleGateway fromEnv(EnvSource env) {
        String baseUrl = require(env, AppConfig.ENV_MODEL_BASE_URL);
        String apiKey = require(env, AppConfig.ENV_MODEL_API_KEY);
        String model = require(env, AppConfig.ENV_MODEL_NAME);
        String rawTimeout = require(env, AppConfig.ENV_MODEL_TIMEOUT_SECONDS);
        int timeout;
        try {
            timeout = Integer.parseInt(rawTimeout.trim());
        } catch (NumberFormatException e) {
            throw new ConfigException(AppConfig.ENV_MODEL_TIMEOUT_SECONDS, "必须是整数秒");
        }
        if (timeout <= 0) {
            throw new ConfigException(AppConfig.ENV_MODEL_TIMEOUT_SECONDS, "必须大于 0");
        }
        return new OpenAiCompatibleGateway(baseUrl, apiKey, model, timeout);
    }

    /** 从进程环境变量构造。 */
    public static OpenAiCompatibleGateway fromEnv() {
        return fromEnv(EnvSource.system());
    }

    /** 本次网关实例累积的调用审计（可按 view / batch 回查）。 */
    public ModelCallAudit audit() {
        return audit;
    }

    @Override
    public String gatewayName() {
        return "openai-compatible";
    }

    @Override
    public AttributionResult attribute(AttributionRequest request) throws ModelGatewayException {
        if (request == null) {
            throw new ModelGatewayException(ModelErrorCode.MODEL_SCHEMA_INVALID, "归因请求不可为 null");
        }
        HttpRequestSpec spec = buildSpec(request);
        long start = System.nanoTime();
        int retries = 0;
        AttributionResult result = null;
        ModelGatewayException failure = null;
        ModelErrorCode error = null;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                HttpResponse response = executor.execute(spec);
                result = classify(response);
                failure = null;
                error = null;
                break;
            } catch (ModelGatewayException mge) {
                error = mge.code();
                failure = new ModelGatewayException(error, redactor.redact(mge.getMessage()), mge.getCause());
            } catch (HttpConnectTimeoutException e) {
                // 连接超时：单独归类，不与读超时混为一谈（ConnectTimeoutException 是 TimeoutException 子类，须先捕获）。
                error = ModelErrorCode.MODEL_CONNECT_TIMEOUT;
                failure = new ModelGatewayException(error, redactor.redact("模型端点连接超时"), e);
            } catch (HttpTimeoutException e) {
                error = ModelErrorCode.MODEL_READ_TIMEOUT;
                failure = new ModelGatewayException(error, redactor.redact("模型响应读超时"), e);
            } catch (IOException e) {
                // 其余连接类 IO 失败归入连接超时码（不含凭据）。
                error = ModelErrorCode.MODEL_CONNECT_TIMEOUT;
                failure = new ModelGatewayException(error,
                        redactor.redact("模型端点连接失败：" + safeMessage(e)), e);
            }

            if (!isRetryable(error) || attempt == MAX_RETRIES) {
                break;
            }
            retries++;
            backoff(attempt);
        }

        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        boolean degraded = (result == null);
        audit.append(new AuditEntry(model, digest(request), elapsedMs, retries, error, degraded,
                request.viewName(), AttributionService.EXAMPLE_BATCH, Instant.now().toString()));

        if (result != null) {
            return result;
        }
        throw failure;
    }

    // ---------- 协议组装 ----------

    private HttpRequestSpec buildSpec(AttributionRequest req) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + apiKey);

        List<Map<String, String>> messages = new ArrayList<>();
        Map<String, String> system = new LinkedHashMap<>();
        system.put("role", "system");
        system.put("content", AttributionPrompt.systemPrompt());
        Map<String, String> user = new LinkedHashMap<>();
        user.put("role", "user");
        user.put("content", toJson(AttributionPrompt.build(req)));
        messages.add(system);
        messages.add(user);

        Map<String, String> responseFormat = new LinkedHashMap<>();
        responseFormat.put("type", "json_object");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("temperature", 0);
        body.put("response_format", responseFormat);

        String url = trimTrailingSlash(baseUrl) + "/v1/chat/completions";
        return new HttpRequestSpec("POST", url, headers, toJson(body), timeoutSeconds);
    }

    // ---------- 响应解析与错误归一 ----------

    private AttributionResult classify(HttpResponse response) throws ModelGatewayException {
        int status = response.status();
        if (status >= 400 && status < 500) {
            throw new ModelGatewayException(ModelErrorCode.MODEL_HTTP_4XX,
                    "模型端点返回 HTTP " + status + "（客户端错误，不重试）");
        }
        if (status >= 500) {
            throw new ModelGatewayException(ModelErrorCode.MODEL_HTTP_5XX,
                    "模型端点返回 HTTP " + status + "（服务端错误，可重试）");
        }
        if (status < 200 || status >= 300) {
            throw new ModelGatewayException(ModelErrorCode.MODEL_EMPTY_RESPONSE,
                    "模型端点返回非成功状态 HTTP " + status);
        }
        String body = response.body();
        if (body == null || body.isBlank()) {
            throw new ModelGatewayException(ModelErrorCode.MODEL_EMPTY_RESPONSE, "模型端点返回空响应");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (JsonProcessingException e) {
            throw new ModelGatewayException(ModelErrorCode.MODEL_EMPTY_RESPONSE,
                    "模型响应外壳不是合法 JSON", e);
        }
        JsonNode choices = root == null ? null : root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw new ModelGatewayException(ModelErrorCode.MODEL_EMPTY_RESPONSE, "模型响应缺少 choices 数组");
        }
        JsonNode content = choices.get(0).path("message").path("content");
        if (content == null || !content.isTextual() || content.asText().isBlank()) {
            throw new ModelGatewayException(ModelErrorCode.MODEL_EMPTY_RESPONSE,
                    "模型响应缺少 choices[0].message.content");
        }
        // content 即模型输出，严格 schema 校验；不合规抛 MODEL_SCHEMA_INVALID（不重试）。
        return AttributionResult.fromJson(content.asText());
    }

    private static boolean isRetryable(ModelErrorCode code) {
        return code == ModelErrorCode.MODEL_CONNECT_TIMEOUT
                || code == ModelErrorCode.MODEL_READ_TIMEOUT
                || code == ModelErrorCode.MODEL_HTTP_5XX;
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(BACKOFF_BASE_MS * (attempt + 1L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------- 脱敏与工具 ----------

    /** 请求摘要：只含结构信息（视图/改动类型/候选边数），不含请求头、体与任何凭据。 */
    private String digest(AttributionRequest req) {
        ChangeKind kind = req.changeKind();
        String raw = "view=" + req.viewName()
                + ", change_kind=" + (kind == null ? "" : kind.wireName())
                + ", candidates=" + req.candidates().size();
        return redactor.redact(raw);
    }

    private static String safeMessage(IOException e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }

    private static String trimTrailingSlash(String url) {
        String s = url;
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("归因请求序列化失败", e);
        }
    }

    private static String require(EnvSource env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new ConfigException(List.of(name));
        }
        return value.trim();
    }

    // ---------- 默认真实传输：JDK HttpClient（产品代码不依赖测试替身） ----------

    private static final class JdkHttpExecutor implements HttpExecutor {
        @Override
        public HttpResponse execute(HttpRequestSpec spec) throws IOException {
            int secs = Math.max(1, spec.timeoutSeconds());
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(secs))
                    .build();
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(spec.url()))
                    .timeout(Duration.ofSeconds(secs))
                    .method(spec.method(), HttpRequest.BodyPublishers.ofString(spec.body(), StandardCharsets.UTF_8));
            spec.headers().forEach(rb::header);
            try {
                java.net.http.HttpResponse<String> resp = client.send(rb.build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                return new HttpResponse(resp.statusCode(), resp.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("模型请求被中断", e);
            }
            // HttpConnectTimeoutException / HttpTimeoutException 为 IOException 子类，自然向上抛出由网关归类。
        }
    }
}
