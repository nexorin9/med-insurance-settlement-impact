package com.chaosforge.medins.sqlparse;

import com.chaosforge.medins.model.EdgeKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Task 4：四类列级边归因与置信分档（过滤边降一档、不计入严格口径）。 */
public class SqlParseEdgeKindTest {

    /** 一段同时含 DIRECT / DERIVED / AGGREGATE / FILTER 四类边的代表 SQL。 */
    private static final String SQL = ""
            + "SELECT o.o_orderkey AS bill_no,\n"
            + "       UPPER(c.c_mktsegment) AS diag_group,\n"
            + "       SUM(l.l_extendedprice) AS fee_total\n"
            + "  FROM orders o\n"
            + "  JOIN customer c ON o.o_custkey = c.c_custkey\n"
            + "  JOIN lineitem l ON o.o_orderkey = l.l_orderkey\n"
            + " WHERE o.o_orderstatus = 'F'\n";

    @Test
    void fourKindsPresent() {
        ParseResult r = SqlParseFixtures.parse("v_edge_kinds", SQL);
        assertTrue(r.success());
        for (EdgeKind k : EdgeKind.values()) {
            assertFalse(r.edgesOfKind(k).isEmpty(), "应命中 " + k.wireName() + " 边");
        }
    }

    @Test
    void kindMetadataConsistentWithAstPosition() {
        ParseResult r = SqlParseFixtures.parse("v_edge_kinds", SQL);
        // DERIVED 记表达式摘要
        assertTrue(r.edgesOfKind(EdgeKind.DERIVED).stream().anyMatch(e -> !e.expression().isBlank()));
        // AGGREGATE 记聚合函数名
        assertTrue(r.edgesOfKind(EdgeKind.AGGREGATE).stream()
                .anyMatch(e -> "SUM".equals(e.aggregateFunction())));
        // FILTER 记 conditionPosition（where / join-on）
        assertTrue(r.edgesOfKind(EdgeKind.FILTER).stream()
                .allMatch(e -> !e.conditionPosition().isBlank()));
        assertTrue(r.edgesOfKind(EdgeKind.FILTER).stream()
                .anyMatch(e -> "where".equals(e.conditionPosition())));
        assertTrue(r.edgesOfKind(EdgeKind.FILTER).stream()
                .anyMatch(e -> "join-on".equals(e.conditionPosition())));
        // DIRECT 边不带聚合函数/表达式
        assertTrue(r.edgesOfKind(EdgeKind.DIRECT).stream()
                .allMatch(e -> e.aggregateFunction().isBlank() && e.expression().isBlank()));
    }

    @Test
    void filterConfidenceLowered() {
        ParseResult r = SqlParseFixtures.parse("v_edge_kinds", SQL);
        double direct = r.edgesOfKind(EdgeKind.DIRECT).get(0).confidence();
        for (ColumnEdge f : r.edgesOfKind(EdgeKind.FILTER)) {
            assertTrue(f.confidence() < direct, "过滤边应比直取边降一档");
        }
        // 置信分档单点：FILTER 不计入严格口径、分档为 2
        assertFalse(EdgeConfidencePolicy.isStrictEligible(EdgeKind.FILTER));
        assertTrue(EdgeConfidencePolicy.isStrictEligible(EdgeKind.DIRECT));
        assertTrue(EdgeConfidencePolicy.isStrictEligible(EdgeKind.DERIVED));
        assertTrue(EdgeConfidencePolicy.isStrictEligible(EdgeKind.AGGREGATE));
        assertEquals(2, EdgeConfidencePolicy.rank(EdgeKind.FILTER));
        assertEquals(1, EdgeConfidencePolicy.rank(EdgeKind.DIRECT));
    }

    @Test
    void visitorCanBeUnitTested() {
        ColumnEdgeVisitor v = ColumnEdgeVisitor.of("v_edge_kinds", SQL);
        v.analyze();
        assertFalse(v.astText().isBlank(), "访问器应持有 Druid 解析产物的规范化文本");
        assertFalse(v.edges().isEmpty());
        assertTrue(v.edges().stream().anyMatch(e -> e.edgeKind() == EdgeKind.AGGREGATE));
        assertTrue(v.edges().stream().anyMatch(e -> e.edgeKind() == EdgeKind.FILTER));
    }
}
