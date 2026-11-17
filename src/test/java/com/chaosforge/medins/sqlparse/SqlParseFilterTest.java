package com.chaosforge.medins.sqlparse;

import com.chaosforge.medins.model.EdgeKind;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Task 6：WHERE / JOIN ON 过滤边、内外连接 joinType、AND-OR 组合、常量/函数/伪列不误判。 */
public class SqlParseFilterTest {

    @Test
    void filterMatrixWhereJoinOnAndLeftJoin() throws Exception {
        ParseResult r = SqlParseFixtures.parseSample("v_filter_matrix");
        assertTrue(r.success());
        List<ColumnEdge> fs = r.edgesOfKind(EdgeKind.FILTER);
        assertFalse(fs.isEmpty());
        assertTrue(fs.stream().anyMatch(e -> "where".equals(e.conditionPosition())), "需含 WHERE 过滤边");
        assertTrue(fs.stream().anyMatch(e -> "join-on".equals(e.conditionPosition())), "需含 JOIN ON 过滤边");
        assertTrue(fs.stream().anyMatch(e -> "left".equals(e.joinType())), "LEFT JOIN 需标注 joinType=left");
        assertTrue(fs.stream().filter(e -> "join-on".equals(e.conditionPosition()))
                .allMatch(e -> !e.joinType().isBlank()), "ON 边均需带 joinType");
    }

    @Test
    void constantsFunctionsPseudoColumnsNotMisread() throws Exception {
        ParseResult r = SqlParseFixtures.parseSample("v_filter_matrix");
        // DATE '2026-01-01'、'F'、1000、DATE 都不得被误判为来源列
        assertFalse(SqlParseFixtures.anySourceColumn(r, "2026-01-01"));
        assertFalse(SqlParseFixtures.anySourceColumn(r, "1000"));
        assertFalse(SqlParseFixtures.anySourceColumn(r, "F"));
        assertFalse(SqlParseFixtures.anySourceColumn(r, "DATE"));
        assertFalse(SqlParseFixtures.anyOutputColumn(r, "1000"));
        assertFalse(SqlParseFixtures.anyOutputColumn(r, "DATE"));
    }

    @Test
    void singleTableWhereAndAndOrCombination() {
        String sql = "SELECT o.o_orderkey AS bill_no FROM orders o\n"
                + " WHERE (o.o_orderstatus = 'F' OR o.o_totalprice > 1000) AND o.o_custkey = 1\n";
        ParseResult r = SqlParseFixtures.parse("v_filter_simple", sql);
        assertTrue(r.success());
        List<ColumnEdge> fs = r.edgesOfKind(EdgeKind.FILTER);
        Set<String> cols = new HashSet<>();
        for (ColumnEdge e : fs) cols.add(e.sourceColumn());
        assertTrue(cols.contains("O_ORDERSTATUS"));
        assertTrue(cols.contains("O_TOTALPRICE"));
        assertTrue(cols.contains("O_CUSTKEY"));
        assertTrue(fs.stream().allMatch(e -> "where".equals(e.conditionPosition())));
        // 3 个直接引用列，不把常量 1000 / 'F' 计进来
        assertEquals(3L, fs.stream().filter(e -> "where".equals(e.conditionPosition())).count());
    }

    @Test
    void rightAndFullJoinTypesRecorded() {
        ParseResult rr = SqlParseFixtures.parse("v_right",
                "SELECT a.c_custkey AS id FROM customer a RIGHT JOIN nation b ON a.c_nationkey = b.n_nationkey");
        assertTrue(rr.edgesOfKind(EdgeKind.FILTER).stream().anyMatch(e -> "right".equals(e.joinType())));
        ParseResult rf = SqlParseFixtures.parse("v_full",
                "SELECT a.c_custkey AS id FROM customer a FULL JOIN nation b ON a.c_nationkey = b.n_nationkey");
        assertTrue(rf.edgesOfKind(EdgeKind.FILTER).stream().anyMatch(e -> "full".equals(e.joinType())));
    }

    @Test
    void innerJoinDefaultType() throws Exception {
        ParseResult r = SqlParseFixtures.parseSample("v_bill_fee");
        assertTrue(r.edgesOfKind(EdgeKind.FILTER).stream()
                .filter(e -> "join-on".equals(e.conditionPosition()))
                .allMatch(e -> "inner".equals(e.joinType())), "普通 JOIN 默认 inner");
    }
}
