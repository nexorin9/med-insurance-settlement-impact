package com.chaosforge.medins.sqlparse;

import com.chaosforge.medins.model.EdgeKind;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** sqlparse 测试公共夹具：资源加载、解析与常用断言 helper（非测试类，不参与 surefire 扫描）。 */
public final class SqlParseFixtures {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SqlParseFixtures() { }

    public static ObjectMapper mapper() { return MAPPER; }

    public static String load(String path) throws IOException {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IOException("classpath 资源不存在：" + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public static String sample(String view) throws IOException {
        return load("samples/views/" + view + ".sql");
    }

    public static String edgecase(String name) throws IOException {
        return load("edgecases/" + name + ".sql");
    }

    public static String golden(String view) throws IOException {
        return load("golden/edges/" + view + ".json");
    }

    public static ParseResult parse(String view, String sql) {
        return new SqlViewParser().parse(view, sql);
    }

    public static ParseResult parseSample(String view) throws IOException {
        return parse(view, sample(view));
    }

    public static boolean hasEdge(ParseResult r, String out, String table, String col, EdgeKind kind) {
        for (ColumnEdge e : r.edges()) {
            if (e.outputColumn().equals(out) && e.sourceTable().equals(table)
                    && e.sourceColumn().equals(col) && e.edgeKind() == kind) return true;
        }
        return false;
    }

    public static boolean anySourceColumn(ParseResult r, String sourceColumn) {
        for (ColumnEdge e : r.edges()) if (e.sourceColumn().equalsIgnoreCase(sourceColumn)) return true;
        return false;
    }

    public static boolean anyOutputColumn(ParseResult r, String outputColumn) {
        for (ColumnEdge e : r.edges()) if (e.outputColumn().equalsIgnoreCase(outputColumn)) return true;
        return false;
    }

    /** 供 golden 比对的四元组键（与快照 expected 项构造方式一致）。 */
    public static List<String> edgeKeys(ParseResult r) {
        List<String> keys = new ArrayList<>();
        for (ColumnEdge e : r.edges()) {
            keys.add(String.join("|", e.outputColumn(), e.sourceTable(), e.sourceColumn(),
                    e.edgeKind() == null ? "" : e.edgeKind().wireName()));
        }
        return keys;
    }

    public static boolean hasReason(ParseResult r, ManualReviewItem.Reason reason) {
        for (ManualReviewItem i : r.reviewItems()) if (i.reason() == reason) return true;
        return false;
    }

    public static long countReason(ParseResult r, ManualReviewItem.Reason reason) {
        return r.reviewItems().stream().filter(i -> i.reason() == reason).count();
    }
}
