package com.chaosforge.medins.attribution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试层本地协议替身（JDK {@code com.sun.net.httpserver.HttpServer}，端口 0 随机）。
 *
 * <p>只在 src/test 下使用：产品代码走真实 {@code java.net.http.HttpClient}，本替身仅用于
 * 断言协议形状与错误分类，不是最终对接面，也不访问真实网络。
 */
public final class LocalStubServer {

    /** 一次替身响应。 */
    public static final class Reply {
        public final int status;
        public final String body;

        public Reply(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    /** 捕获到的一次请求（头键统一小写，便于大小写无关断言）。 */
    public static final class Captured {
        public final String method;
        public final String path;
        public final String body;
        public final Map<String, String> headers;

        private Captured(String method, String path, String body, Map<String, String> headers) {
            this.method = method;
            this.path = path;
            this.body = body;
            this.headers = headers;
        }
    }

    /** 响应定制器。 */
    @FunctionalInterface
    public interface Responder {
        Reply respond(Captured request);
    }

    private final HttpServer server;
    private final int port;
    private final List<Captured> captured = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger hits = new AtomicInteger();
    private volatile Responder responder;

    public LocalStubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", this::handle);
        server.setExecutor(null);
        server.start();
    }

    public void setResponder(Responder r) {
        this.responder = r;
    }

    /** 恢复默认合法 OpenAI 兼容响应。 */
    public void reset() {
        this.responder = null;
    }

    public void clearCaptured() {
        captured.clear();
        hits.set(0);
    }

    public int port() {
        return port;
    }

    public int hits() {
        return hits.get();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    /** 另一条 base_url（追加路径前缀），仍指向同一替身，用于服务可替换性断言。 */
    public String baseUrl(String pathPrefix) {
        return "http://127.0.0.1:" + port + pathPrefix;
    }

    public List<Captured> captured() {
        synchronized (captured) {
            return List.copyOf(captured);
        }
    }

    public Captured last() {
        synchronized (captured) {
            return captured.isEmpty() ? null : captured.get(captured.size() - 1);
        }
    }

    public void stop() {
        server.stop(0);
    }

    // ---------- 协议辅助：供测试构造各种模型响应 ----------

    /** 合法的四档归因输出（content 内层 JSON 字符串）。 */
    public static String validContentJson() {
        try {
            ObjectMapper m = new ObjectMapper();
            ObjectNode content = m.createObjectNode();
            ArrayNode arr = content.putArray("affected");
            ObjectNode a = arr.addObject();
            a.put("list_field", "医疗总费用金额合计");
            a.put("section", "医疗收费信息");
            a.put("source_column", "fee_amt");
            a.put("verdict", "实质受影响");
            a.put("confidence", 0.93);
            a.put("reason_zh", "取数列与该清单字段口径一致");
            content.put("summary_zh", "共 1 条受影响字段");
            return m.writeValueAsString(content);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 把 content 包成 OpenAI 兼容响应外壳（choices[0].message.content）。 */
    public static String openAiBody(String content) {
        try {
            ObjectMapper m = new ObjectMapper();
            ObjectNode root = m.createObjectNode();
            ArrayNode choices = root.putArray("choices");
            ObjectNode message = choices.addObject().putObject("message");
            message.put("content", content);
            return m.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 默认合法响应。 */
    public static String defaultBody() {
        return openAiBody(validContentJson());
    }

    // ---------- 内部 ----------

    private void handle(HttpExchange ex) throws IOException {
        Captured c = capture(ex);
        captured.add(c);
        hits.incrementAndGet();
        Responder r = responder;
        Reply reply;
        try {
            reply = r == null ? new Reply(200, defaultBody()) : r.respond(c);
        } catch (Exception e) {
            reply = new Reply(500, "{\"error\":\"stub-failure\"}");
        }
        byte[] out = reply.body == null ? new byte[0] : reply.body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        if (out.length == 0) {
            ex.sendResponseHeaders(reply.status, -1);
        } else {
            ex.sendResponseHeaders(reply.status, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        }
        ex.close();
    }

    private static Captured capture(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : ex.getRequestHeaders().entrySet()) {
            if (e.getValue() != null && !e.getValue().isEmpty()) {
                headers.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().get(0));
            }
        }
        return new Captured(method, path, body, Collections.unmodifiableMap(headers));
    }
}
