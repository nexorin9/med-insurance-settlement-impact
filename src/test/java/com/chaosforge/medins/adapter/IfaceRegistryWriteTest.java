package com.chaosforge.medins.adapter;

import com.chaosforge.medins.sqlparse.SqlDigest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 40：幂等写入（Idempotency-Key）、退避重试与变更单降级导出。
 * 本地协议替身用注入的 HttpTransport（测试层），不是最终对接面；不访问真实网络。
 */
class IfaceRegistryWriteTest {

    private static final String PATH = "/api/v1/interface-entries/E1/field-change-impacts";

    private static String key() {
        return SqlDigest.idempotencyKey("V_BILL_FEE", "DIGEST", "FEE_AMT");
    }

    private static WriteBackRequest request() {
        return WriteBackRequest.builder()
                .idempotencyKey(key())
                .viewName("V_BILL_FEE")
                .viewDialect("ORACLE")
                .sqlDigest("DIGEST")
                .changedField("FEE_AMT")
                .changeKind("logic_changed")
                .addAffectedListField("基本信息", "清单流水号", "BILL_NO", "direct", 0.85, "<EXAMPLE_BATCH>")
                .affectedBatches(List.of("<EXAMPLE_BATCH>"))
                .summaryZh("改动语义归因摘要（草稿）")
                .confirmedBy("专员A")
                .confirmedAt("2026-11-09T08:50:00Z")
                .occurredAt("2026-11-09T09:40:00Z")
                .build();
    }

    @Test
    void postHasIdempotencyKeyHeaderAndFullBodyFields() throws Exception {
        FakeTransport fake = new FakeTransport();
        IfaceRegistryClient client = new IfaceRegistryClient("http://registry.local", "TOKEN", fake);
        WriteBackRequest req = request();
        WriteResponse resp = client.post(PATH, req.toJson(), key());

        assertTrue(resp.ok());
        assertEquals(1, fake.calls.size());
        Captured c = fake.calls.get(0);
        assertEquals("POST", c.method);
        assertEquals(key(), c.headers.get("Idempotency-Key"), "请求头含 Idempotency-Key");
        assertEquals("Bearer TOKEN", c.headers.get("Authorization"));

        @SuppressWarnings("unchecked")
        Map<String, Object> body = new ObjectMapper().readValue(c.body, Map.class);
        assertEquals(key(), body.get("idempotency_key"));
        assertEquals("V_BILL_FEE", body.get("view_name"));
        assertEquals("ORACLE", body.get("view_dialect"));
        assertEquals("DIGEST", body.get("sql_digest"));
        assertEquals("FEE_AMT", body.get("changed_field"));
        assertEquals("logic_changed", body.get("change_kind"));
        assertEquals("专员A", body.get("confirmed_by"));
        assertEquals("2026-11-09T08:50:00Z", body.get("confirmed_at"));
        assertEquals("2026-11-09T09:40:00Z", body.get("occurred_at"));
        assertTrue(body.containsKey("summary_zh"));
        assertTrue(body.get("affected_batches") instanceof List);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) body.get("affected_list_fields");
        assertEquals(1, fields.size());
        Map<String, Object> f = fields.get(0);
        assertEquals("基本信息", f.get("section"));
        assertEquals("清单流水号", f.get("field"));
        assertEquals("BILL_NO", f.get("source_column"));
        assertEquals("direct", f.get("impact"));
        assertEquals("<EXAMPLE_BATCH>", f.get("batch"));
        assertTrue(f.containsKey("confidence"));
    }

    @Test
    void duplicateSubmit_onlyOneLedgerEntry() {
        FakeTransport fake = new FakeTransport();
        IfaceRegistryClient client = new IfaceRegistryClient("http://registry.local", "TOKEN", fake);
        WriteBackRequest req = request();

        WriteResponse first = client.post(PATH, req.toJson(), key());
        WriteResponse second = client.post(PATH, req.toJson(), key());

        assertEquals(1, client.submittedKeyCount(), "同一键只登记一次");
        assertEquals(1, fake.ledger.size(), "台账侧重复提交只落一条");
        assertEquals(1, fake.calls.size(), "客户端内已提交键集合阻止第二次网络写入");
        assertTrue(second.duplicate(), "第二次提交命中客户端缓存");
        assertEquals(key(), second.idempotencyKey(), "响应回带同一键");
        assertFalse(first.duplicate());

        // 另一个客户端（模拟不同进程）提交同一键，台账侧按覆盖语义仍只有一条
        new IfaceRegistryClient("http://registry.local", "TOKEN", fake)
                .post(PATH, req.toJson(), key());
        assertEquals(1, fake.ledger.size(), "台账侧覆盖语义：同键不追加第二条");
    }

    @Test
    void fourXxRetriesAtMostTwoTimes() {
        FakeTransport fake = new FakeTransport();
        fake.statuses.addAll(List.of(400, 400, 400));
        IfaceRegistryClient client = new IfaceRegistryClient("http://registry.local", "TOKEN", fake);
        client.setRetryBackoffBaseMillis(0); // 测试不真实等待

        IfaceRegistryException ex = assertThrows(IfaceRegistryException.class,
                () -> client.post(PATH, request().toJson(), key()));
        assertEquals(400, ex.status());
        assertEquals(3, fake.attempts, "1 次初始 + 至多 2 次退避重试");
        assertTrue(fake.attempts <= 3, "重试次数 ≤2");
    }

    @Test
    void degradationChangeOrderFieldsMatchRequestBody(@TempDir Path dir) throws Exception {
        WriteBackRequest req = request();
        Path file = ChangeOrderExporter.export(req, dir);
        assertTrue(Files.isRegularFile(file));

        String json = Files.readString(file, StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked")
        Map<String, Object> doc = new ObjectMapper().readValue(json, Map.class);
        assertEquals(ChangeOrderExporter.VERSION, doc.get("change_order_version"), "带版本号");
        assertEquals("待人工录入院内台账", doc.get("notice"), "文件头注明人工录入");

        // 降级导出的变更单字段与请求体逐字段一致
        @SuppressWarnings("unchecked")
        Map<String, Object> expected = new ObjectMapper().readValue(req.toJson(), Map.class);
        for (String k : req.toMap().keySet()) {
            assertEquals(expected.get(k), doc.get(k), "变更单字段 " + k + " 须与请求体一致");
        }
    }

    /** 记录一次调用。 */
    private static final class Captured {
        final String method;
        final String url;
        final Map<String, String> headers;
        final String body;

        Captured(String method, String url, Map<String, String> headers, String body) {
            this.method = method;
            this.url = url;
            this.headers = headers;
            this.body = body;
        }
    }

    /** 台账协议替身：按 Idempotency-Key 覆盖存储，可编排 4xx 序列。 */
    private static final class FakeTransport implements HttpTransport {
        final List<Captured> calls = new ArrayList<>();
        final Map<String, String> ledger = new LinkedHashMap<>();
        final Queue<Integer> statuses = new ArrayDeque<>();
        int attempts;

        @Override
        public RawResponse execute(String method, String url, Map<String, String> headers,
                                   String body, int timeoutSeconds) throws IOException {
            attempts++;
            calls.add(new Captured(method, url, new LinkedHashMap<>(headers), body));
            int status = statuses.isEmpty() ? 200 : statuses.poll();
            if (status >= 200 && status < 300) {
                String k = headers.get("Idempotency-Key");
                if (k != null) {
                    ledger.put(k, body); // 覆盖语义
                }
                return new RawResponse(status, "{\"ok\":true,\"idempotency_key\":\"" + k + "\"}");
            }
            return new RawResponse(status, "{\"error\":\"bad\"}");
        }
    }
}
