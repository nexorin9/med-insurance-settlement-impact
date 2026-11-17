package com.chaosforge.medins.adapter;

import com.chaosforge.medins.config.SecretRedactor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 院内接口管理平台读写客户端（HTTP 出站）。
 *
 * <p>读：{@code GET {base}/api/v1/interface-entries?system=HIS}；
 * 写：{@code POST {base}/api/v1/interface-entries/{entry_id}/field-change-impacts}；
 * 两处均带 {@code Authorization: Bearer <token>}。
 *
 * <p>规则：
 * <ul>
 *   <li>非 2xx 一律显式抛 {@link IfaceRegistryException}（含状态码与响应摘要），不吞异常、不返回空集合；</li>
 *   <li>{@link HttpTransport} 可注入（默认 JDK HttpClient），本地协议替身只在测试层；</li>
 *   <li>读超时可配、响应体大小设上限；</li>
 *   <li>写用 {@code Idempotency-Key}，客户端维护已提交键集合，重复提交只落一条；</li>
 *   <li>4xx/5xx/超时指数退避重试 ≤2 次，仍失败由上层置 PENDING_WRITEBACK + 导出变更单；</li>
 *   <li>异常与日志文本统一过 {@link SecretRedactor}，不回显令牌。</li>
 * </ul>
 */
public final class IfaceRegistryClient {

    /** 台账读端点（GET）。 */
    public static final String GET_ENTRIES_PATH = "/api/v1/interface-entries?system=HIS";
    /** 台账写端点模板（POST）。 */
    public static final String WRITE_IMPACTS_PATH_TEMPLATE =
            "/api/v1/interface-entries/{entry_id}/field-change-impacts";

    private static final int DEFAULT_TIMEOUT_SECONDS = 10;
    private static final long DEFAULT_MAX_RESPONSE_BYTES = 1_048_576L; // 1 MiB
    private static final int MAX_RETRIES = 2;
    private static final int SNIPPET_LIMIT = 200;

    private final String baseUrl;
    private final String token;
    private final HttpTransport transport;
    private final SecretRedactor redactor;

    private volatile int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
    private volatile long maxResponseBytes = DEFAULT_MAX_RESPONSE_BYTES;
    private volatile long retryBackoffBaseMillis = 5L;

    // 客户端幂等：已成功提交的键集合 + 每键最后一次响应
    private final Set<String> submittedKeys = ConcurrentHashMap.newKeySet();
    private final Map<String, WriteResponse> lastByKey = new ConcurrentHashMap<>();

    public IfaceRegistryClient(String baseUrl, String token) {
        this(baseUrl, token, null);
    }

    public IfaceRegistryClient(String baseUrl, String token, HttpTransport transport) {
        this.baseUrl = stripTrailingSlash(baseUrl == null ? "" : baseUrl);
        this.token = token == null ? "" : token;
        this.redactor = SecretRedactor.of(List.of(this.token));
        this.transport = transport == null ? new JdkHttpTransport() : transport;
    }

    /** GET 台账：2xx 返回响应体文本；非 2xx 显式抛错。 */
    public String get(String path) throws IfaceRegistryException {
        String url = resolve(path);
        Map<String, String> headers = baseHeaders();
        headers.put("Accept", "application/json");
        try {
            RawResponse resp = transport.execute("GET", url, headers, null, timeoutSeconds);
            if (!resp.ok()) {
                throw new IfaceRegistryException(
                        "GET 台账失败，状态码 " + resp.status() + "；响应摘要：" + snippet(resp.body()),
                        resp.status(), snippet(resp.body()));
            }
            return resp.body();
        } catch (IOException e) {
            throw new IfaceRegistryException("GET 台账网络异常：" + redact(String.valueOf(e.getMessage())),
                    -1, "", e);
        }
    }

    /**
     * POST 台账回写：带 Bearer 与 Idempotency-Key；重复提交只落一条；
     * 4xx/5xx/超时指数退避重试 ≤2 次，仍失败抛出带状态码的错误。
     */
    public WriteResponse post(String path, String jsonBody, String idempotencyKey) throws IfaceRegistryException {
        String key = idempotencyKey == null ? "" : idempotencyKey;
        // 客户端幂等：已提交的键直接回带上一次成功响应，不产生第二条记录
        if (submittedKeys.contains(key)) {
            WriteResponse cached = lastByKey.get(key);
            if (cached != null) {
                return cached.withDuplicate(true);
            }
        }
        String url = resolve(path);
        Map<String, String> headers = baseHeaders();
        headers.put("Content-Type", "application/json");
        headers.put("Idempotency-Key", key);

        IfaceRegistryException last = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                RawResponse resp = transport.execute("POST", url, headers, jsonBody, timeoutSeconds);
                if (resp.ok()) {
                    WriteResponse wr = new WriteResponse(resp.status(), resp.body(), key, false);
                    submittedKeys.add(key);
                    lastByKey.put(key, wr);
                    return wr;
                }
                last = new IfaceRegistryException(
                        "POST 台账失败，状态码 " + resp.status() + "；响应摘要：" + snippet(resp.body()),
                        resp.status(), snippet(resp.body()));
            } catch (IOException e) {
                last = new IfaceRegistryException(
                        "POST 台账网络异常：" + redact(String.valueOf(e.getMessage())), -1, "", e);
            }
            if (attempt < MAX_RETRIES) {
                backoff(attempt);
            }
        }
        // 仍失败：交给上层置 PENDING_WRITEBACK + 导出变更单，绝不静默丢失
        throw last;
    }

    /** 已成功提交的幂等键数量（供测试与自检）。 */
    public int submittedKeyCount() {
        return submittedKeys.size();
    }

    public void setTimeoutSeconds(int seconds) {
        this.timeoutSeconds = Math.max(1, seconds);
    }

    public int timeoutSeconds() {
        return timeoutSeconds;
    }

    public void setMaxResponseBytes(long bytes) {
        this.maxResponseBytes = Math.max(1L, bytes);
    }

    public long maxResponseBytes() {
        return maxResponseBytes;
    }

    /** 退避基值（毫秒）；指数退避为 base * 2^attempt。测试可调小以加速。 */
    public void setRetryBackoffBaseMillis(long millis) {
        this.retryBackoffBaseMillis = Math.max(0L, millis);
    }

    private void backoff(int attempt) {
        long millis = retryBackoffBaseMillis << attempt;
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Map<String, String> baseHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + token);
        return headers;
    }

    private String resolve(String path) {
        if (path == null || path.isBlank()) {
            return baseUrl;
        }
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }
        return baseUrl + (path.startsWith("/") ? path : "/" + path);
    }

    private String snippet(String body) {
        String redacted = redact(body == null ? "" : body);
        return redacted.length() > SNIPPET_LIMIT ? redacted.substring(0, SNIPPET_LIMIT) + "…" : redacted;
    }

    private String redact(String text) {
        return redactor.redact(text);
    }

    private static String stripTrailingSlash(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(0, end);
    }

    /** 默认 JDK HttpClient 传输：HTTP/1.1、按次超时、响应体大小上限、异常统一为 IOException。 */
    private final class JdkHttpTransport implements HttpTransport {
        private final HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS))
                .build();

        @Override
        public RawResponse execute(String method, String url, Map<String, String> headers,
                                   String body, int timeoutSeconds) throws IOException {
            HttpRequest.BodyPublisher publisher = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                    .method(method, publisher);
            headers.forEach(builder::header);

            HttpResponse<InputStream> resp;
            try {
                resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("HTTP 调用被中断", e);
            }
            long cap = maxResponseBytes;
            String text;
            try (InputStream in = resp.body(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                long total = 0L;
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > cap) {
                        throw new IOException("响应体超过大小上限 " + cap + " 字节，已中止读取");
                    }
                    out.write(buf, 0, n);
                }
                text = out.toString(StandardCharsets.UTF_8);
            }
            return new RawResponse(resp.statusCode(), text);
        }
    }
}
