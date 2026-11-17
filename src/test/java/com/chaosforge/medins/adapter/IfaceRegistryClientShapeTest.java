package com.chaosforge.medins.adapter;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 39：院内接口管理平台读写客户端（Bearer 鉴权 + 非 2xx 显式报错）。
 * 本地协议替身用 JDK HttpServer（随机端口 0），不访问真实网络。
 */
class IfaceRegistryClientShapeTest {

    private HttpServer server;
    private Recorder recorder;
    private String base;

    @BeforeEach
    void setUp() throws IOException {
        recorder = new Recorder();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new RecordingHandler(recorder));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void getPathMethodAndBearerHeader() {
        String body = new IfaceRegistryClient(base, "TOKEN-ABC")
                .get(IfaceRegistryClient.GET_ENTRIES_PATH);
        assertEquals("GET", recorder.method);
        assertEquals("/api/v1/interface-entries", recorder.path);
        assertEquals("system=HIS", recorder.query);
        assertEquals("Bearer TOKEN-ABC", recorder.auth);
        assertTrue(body.contains("ok"));
    }

    @Test
    void postPathMethodAndHeaders() {
        WriteResponse resp = new IfaceRegistryClient(base, "TOKEN-ABC")
                .post("/api/v1/interface-entries/E1/field-change-impacts", "{\"x\":1}", "KEY-1");
        assertEquals("POST", recorder.method);
        assertEquals("/api/v1/interface-entries/E1/field-change-impacts", recorder.path);
        assertEquals("Bearer TOKEN-ABC", recorder.auth);
        assertEquals("KEY-1", recorder.idem, "写请求须带 Idempotency-Key");
        assertNotNull(recorder.contentType);
        assertTrue(recorder.contentType.contains("application/json"));
        assertTrue(resp.ok());
        assertEquals("{\"x\":1}", recorder.body);
    }

    @Test
    void non2xxThrowsWithStatusAndResponseBodySnippet() {
        recorder.status = 503;
        recorder.responseBody = "{\"error\":\"台账维护中\"}";
        IfaceRegistryException ex = assertThrows(IfaceRegistryException.class,
                () -> new IfaceRegistryClient(base, "TOKEN-ABC")
                        .get(IfaceRegistryClient.GET_ENTRIES_PATH));
        assertEquals(503, ex.status());
        assertTrue(ex.getMessage().contains("503"));
        assertTrue(ex.getMessage().contains("台账维护中") || ex.bodySnippet().contains("台账维护中"),
                "错误须带响应摘要");
    }

    @Test
    void responseBodySizeIsCapped() {
        recorder.responseBody = "X".repeat(500);
        IfaceRegistryClient client = new IfaceRegistryClient(base, "TOKEN-ABC");
        client.setMaxResponseBytes(16);
        IfaceRegistryException ex = assertThrows(IfaceRegistryException.class,
                () -> client.get(IfaceRegistryClient.GET_ENTRIES_PATH));
        assertTrue(ex.getMessage().contains("上限"), "超大响应体须被上限中止");
    }

    @Test
    void timeoutIsConfigurable() {
        IfaceRegistryClient client = new IfaceRegistryClient(base, "TOKEN-ABC");
        client.setTimeoutSeconds(3);
        assertEquals(3, client.timeoutSeconds());
        // 可配超时下仍能完成本地调用
        assertTrue(client.get(IfaceRegistryClient.GET_ENTRIES_PATH).contains("ok"));
    }

    /** 记录一次请求的替身状态。 */
    private static final class Recorder {
        volatile int status = 200;
        volatile String responseBody = "{\"ok\":true}";
        volatile String method;
        volatile String path;
        volatile String query;
        volatile String auth;
        volatile String idem;
        volatile String contentType;
        volatile String body;
    }

    private static final class RecordingHandler implements HttpHandler {
        private final Recorder r;

        RecordingHandler(Recorder r) {
            this.r = r;
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            r.method = ex.getRequestMethod();
            r.path = ex.getRequestURI().getPath();
            r.query = ex.getRequestURI().getQuery();
            r.auth = ex.getRequestHeaders().getFirst("Authorization");
            r.idem = ex.getRequestHeaders().getFirst("Idempotency-Key");
            r.contentType = ex.getRequestHeaders().getFirst("Content-Type");
            r.body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] out = r.responseBody.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(r.status, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        }
    }
}
