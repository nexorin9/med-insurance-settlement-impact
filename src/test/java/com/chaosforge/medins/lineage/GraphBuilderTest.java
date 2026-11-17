package com.chaosforge.medins.lineage;

import com.chaosforge.medins.model.EdgeKind;
import com.chaosforge.medins.sqlparse.ColumnEdge;
import com.chaosforge.medins.sqlparse.ParseResult;
import com.chaosforge.medins.sqlparse.SqlDialect;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.chaosforge.medins.lineage.LineageFixtures.fk;
import static com.chaosforge.medins.lineage.LineageFixtures.keys;
import static com.chaosforge.medins.lineage.LineageFixtures.set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task17：图构建入口、跨视图消费链、字段键规范化、重复边合并、同名不误合并。 */
class GraphBuilderTest {

    private ColumnEdge ce(String view, String out, String srcTable, String srcCol, EdgeKind kind) {
        return ColumnEdge.builder()
                .viewName(view).outputColumn(out)
                .sourceTable(srcTable).sourceColumn(srcCol)
                .edgeKind(kind).build();
    }

    private ParseResult pr(String viewName, List<ColumnEdge> edges) {
        return ParseResult.success(viewName, SqlDialect.ORACLE, "DIGEST", edges, List.of());
    }

    @Test
    void crossViewConsumptionChainIsBuilt() {
        // V_A.OUT1 来自底层表 ORDERS；V_B 引用「视图 V_A 的输出列 OUT1」而非直接连底层表
        List<ParseResult> results = List.of(
                pr("V_A", List.of(ce("V_A", "OUT1", "ORDERS", "O_ORDERKEY", EdgeKind.DIRECT))),
                pr("V_B", List.of(ce("V_B", "OUT2", "V_A", "OUT1", EdgeKind.DIRECT))));
        FieldLineageGraph g = GraphBuilder.build(results);

        assertEquals(2, g.edgeCount());
        assertEquals(3, g.nodes().size());
        assertTrue(g.downstream(fk("V_A.OUT1")).contains(fk("V_B.OUT2")),
                "边应从 A 的输出列指向 B 的输出列，形成跨视图消费链");
        assertTrue(g.upstream(fk("V_B.OUT2")).contains(fk("V_A.OUT1")));
        assertEquals(Set.of(EdgeKind.DIRECT), g.kindsOf(fk("V_A.OUT1"), fk("V_B.OUT2")));
        assertTrue(g.upstream(fk("V_A.OUT1")).contains(fk("ORDERS.O_ORDERKEY")),
                "V_A 自身仍挂在底层表列上");
    }

    @Test
    void fieldKeysAreNormalizedUppercase() {
        // 输入故意用小写，构图后字段键应统一为大写规范形式
        List<ParseResult> results = List.of(
                pr("v_low", List.of(ce("v_low", "bill_no", "orders", "o_orderkey", EdgeKind.DIRECT))));
        FieldLineageGraph g = GraphBuilder.build(results);

        assertTrue(g.nodes().contains(fk("V_LOW.BILL_NO")));
        assertTrue(g.nodes().contains(fk("ORDERS.O_ORDERKEY")));
        for (var node : g.nodes()) {
            assertEquals(node.key().toUpperCase(java.util.Locale.ROOT), node.key(),
                    "字段键必须大写：" + node.key());
        }
    }

    @Test
    void duplicateEdgesMergeKeepingAllKinds() {
        // 同一对 (T.C -> V_A.OUT) 挂三种 edge_kind，应合并为一条边且不丢信息
        List<ParseResult> results = List.of(
                pr("V_A", List.of(
                        ce("V_A", "OUT", "T", "C", EdgeKind.DIRECT),
                        ce("V_A", "OUT", "T", "C", EdgeKind.AGGREGATE),
                        ce("V_A", "OUT", "T", "C", EdgeKind.FILTER))));
        FieldLineageGraph g = GraphBuilder.build(results);

        assertEquals(1, g.edgeCount(), "重复边合并为一条");
        assertEquals(Set.of(EdgeKind.DIRECT, EdgeKind.AGGREGATE, EdgeKind.FILTER),
                g.kindsOf(fk("T.C"), fk("V_A.OUT")), "合并后 kind 集合完整");
        assertEquals(set("T.C", "V_A.OUT"), keys(g.nodes()));
    }

    @Test
    void sameColumnInDifferentViewsIsNotMerged() {
        List<ParseResult> results = List.of(
                pr("V_A", List.of(ce("V_A", "FEE_AMT", "ORDERS", "O_TOTALPRICE", EdgeKind.DIRECT))),
                pr("V_B", List.of(ce("V_B", "FEE_AMT", "LINEITEM", "L_EXTENDEDPRICE", EdgeKind.DIRECT))));
        FieldLineageGraph g = GraphBuilder.build(results);

        assertNotEquals(fk("V_A.FEE_AMT"), fk("V_B.FEE_AMT"));
        assertTrue(g.nodes().contains(fk("V_A.FEE_AMT")));
        assertTrue(g.nodes().contains(fk("V_B.FEE_AMT")));
        assertEquals(2, g.edgeCount());
    }

    @Test
    void unresolvedStarEdgesAreSkipped() {
        // SELECT * 的 unresolved 边不应进入字段级图
        ColumnEdge star = ColumnEdge.builder()
                .viewName("V_STAR").outputColumn("*")
                .sourceTable("").sourceColumn("")
                .edgeKind(EdgeKind.DIRECT).unresolved(true).build();
        List<ParseResult> results = List.of(pr("V_STAR", List.of(star)));
        FieldLineageGraph g = GraphBuilder.build(results);
        assertEquals(0, g.edgeCount());
    }

    @Test
    void buildFromSqlMapSmoke() {
        // 空 map 不触发任何 SQL 解析，仅验证 build(Map) 与契约层 parseAll 的接线
        FieldLineageGraph g = GraphBuilder.build(Map.of());
        assertNotNull(g);
        assertEquals(0, g.edgeCount(), "空输入应得空图");
        assertTrue(g.nodes().isEmpty());
    }
}
