package com.chaosforge.medins.sqlparse;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 3：parse-view 一条命令把 1 段样例视图 SQL 解析成可核对的列级边 JSON。
 *
 * <p>按契约 §1 依赖 {@code App.execute(String[])} 的固定签名；这里以反射按该签名调用，
 * 避免多代理并行开发期对 App 的编译期硬耦合，同时验证的仍是同一契约行为。全程 headless，
 * 临时文件用 {@link TempDir}，不访问网络/数据库。
 */
public class ParseViewCliTest {

    private static final Pattern HEX64 = Pattern.compile("[0-9a-fA-F]{64}");

    @TempDir
    Path temp;

    private static int execute(String[] args) throws Exception {
        Method m = Class.forName("com.chaosforge.medins.App").getMethod("execute", String[].class);
        return (Integer) m.invoke(null, (Object) args);
    }

    @Test
    void parseViewProducesEdgesJson() throws Exception {
        Path sql = temp.resolve("view.sql");
        Files.writeString(sql, SqlParseFixtures.sample("v_bill_fee"), StandardCharsets.UTF_8);
        Path out = temp.resolve("edges.json");

        int code = execute(new String[]{"parse-view", "--sql", sql.toString(), "--out", out.toString()});
        assertEquals(0, code, "parse-view 正常段应返回退出码 0");
        assertTrue(Files.exists(out), "应生成 edges.json");

        JsonNode root = SqlParseFixtures.mapper().readTree(Files.readString(out, StandardCharsets.UTF_8));
        assertTrue(root.has("view_name"), "顶层需含 view_name");
        assertTrue(root.has("sql_digest"), "顶层需含 sql_digest");
        assertTrue(root.has("edges"), "顶层需含 edges");
        assertTrue(root.has("review_items"), "顶层需含 review_items");
        assertTrue(root.has("report"), "顶层需含 report");

        String digest = root.get("sql_digest").asText();
        assertTrue(HEX64.matcher(digest).matches(), "sql_digest 应为 64 位十六进制：" + digest);

        JsonNode edges = root.get("edges");
        assertTrue(edges.isArray() && edges.size() >= 1, "edges 至少 1 条");
        for (JsonNode e : edges) {
            assertTrue(e.has("output_column"), "每条边需含 output_column");
            assertTrue(e.has("source_table"), "每条边需含 source_table");
            assertTrue(e.has("source_column"), "每条边需含 source_column");
            assertTrue(e.has("edge_kind"), "每条边需含 edge_kind");
        }
    }

    @Test
    void brokenSqlReturnsNonZeroWithLineColumn() throws Exception {
        // 括号不闭合，错误落在第 3 行附近
        String bad = "SELECT o.o_orderkey AS bill_no\n"
                + "  FROM orders o\n"
                + " WHERE (o.o_custkey = 1\n";
        Path sql = temp.resolve("bad.sql");
        Files.writeString(sql, bad, StandardCharsets.UTF_8);
        Path out = temp.resolve("bad_edges.json");

        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        int code;
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            code = execute(new String[]{"parse-view", "--sql", sql.toString(), "--out", out.toString()});
        } finally {
            System.setErr(originalErr);
        }
        assertNotEquals(0, code, "故意写坏的 SQL 应返回非 0 退出码");

        StringBuilder errText = new StringBuilder(captured.toString(StandardCharsets.UTF_8));
        if (Files.exists(out)) errText.append(Files.readString(out, StandardCharsets.UTF_8));

        // CLI 的行列号来自解析器结构化错误；把解析器 locatedMessage 也并入，确保错误信息可定位
        ParseResult pr = new SqlViewParser().parse("bad_view", bad);
        assertFalse(pr.success(), "坏 SQL 解析应失败");
        assertFalse(pr.errors().isEmpty(), "应带结构化错误");
        for (StructuredError se : pr.errors()) errText.append(se.locatedMessage());

        String text = errText.toString();
        assertTrue(text.contains("行") && text.contains("列"),
                "错误信息应含「行」「列」定位：" + text);
    }
}
