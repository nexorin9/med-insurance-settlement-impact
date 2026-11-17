package com.chaosforge.medins.lineage;

import com.chaosforge.medins.model.EdgeKind;
import com.chaosforge.medins.model.FieldKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.chaosforge.medins.lineage.LineageFixtures.edge;
import static com.chaosforge.medins.lineage.LineageFixtures.fk;
import static com.chaosforge.medins.lineage.LineageFixtures.keys;
import static com.chaosforge.medins.lineage.LineageFixtures.set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task15 / Task16：字段级双方向邻接表、边元数据合并、同名消歧、拓扑序、环检测、根/终点、一跳 parents。 */
class LineageGraphTest {

    /** 菱形依赖 + 跨视图 JOIN + 同名字段的小图（无环）。 */
    private FieldLineageGraph diamond() {
        FieldLineageGraph g = new FieldLineageGraph();
        edge(g, "TA.K", "VA.A", EdgeKind.DIRECT);
        edge(g, "TB.K", "VB.A", EdgeKind.DIRECT);
        edge(g, "VA.A", "VC.M", EdgeKind.DIRECT);
        edge(g, "VB.A", "VC.M", EdgeKind.DERIVED);
        edge(g, "VC.M", "VD.Z", EdgeKind.AGGREGATE);
        edge(g, "VC.M", "VE.Z", EdgeKind.DIRECT);
        return g;
    }

    @Test
    void adjacencyIsSymmetricAndNoEdgeLost() {
        FieldLineageGraph g = diamond();
        assertEquals(6, g.edgeCount(), "边数应与输入去重边数一致");
        assertEquals(6, g.edges().size());
        assertEquals(7, g.nodes().size());
        for (GraphEdge e : g.edges()) {
            assertTrue(g.downstream(e.from()).contains(e.to()), "DOWNSTREAM 邻接应含 to：" + e);
            assertTrue(g.upstream(e.to()).contains(e.from()), "UPSTREAM 邻接应含 from：" + e);
            assertTrue(g.parents(e.to()).contains(e.from()), "一跳 parents 应含 from：" + e);
            assertTrue(g.kindsOf(e.from(), e.to()).containsAll(e.kinds()));
        }
    }

    @Test
    void sameColumnInDifferentViewsIsNotMerged() {
        FieldLineageGraph g = diamond();
        FieldKey a = fk("VA.A");
        FieldKey b = fk("VB.A");
        assertNotEquals(a, b, "不同视图的同名列 A 必须是不同节点，不得误合并");
        assertTrue(g.nodes().contains(a));
        assertTrue(g.nodes().contains(b));
        assertEquals(set("VA.A", "VB.A"), keys(g.upstream(fk("VC.M"))),
                "VC.M 应有两个来自不同视图的父节点");
    }

    @Test
    void multipleEdgeKindsMergeWithoutLosingInformation() {
        FieldLineageGraph g = diamond();
        assertEquals(Set.of(EdgeKind.DIRECT), g.kindsOf(fk("VA.A"), fk("VC.M")));
        assertEquals(Set.of(EdgeKind.DERIVED), g.kindsOf(fk("VB.A"), fk("VC.M")));
        assertEquals(Set.of(EdgeKind.AGGREGATE), g.kindsOf(fk("VC.M"), fk("VD.Z")));

        // 同一 (from,to) 再挂一条 FILTER 边：应合并成一条带多重 kind 的边
        edge(g, "VA.A", "VC.M", EdgeKind.FILTER);
        assertEquals(Set.of(EdgeKind.DIRECT, EdgeKind.FILTER),
                g.kindsOf(fk("VA.A"), fk("VC.M")), "多重 edge_kind 合并去重不丢信息");
        assertEquals(6, g.edgeCount(), "合并后边数不变");
        assertTrue(g.edges().stream()
                .anyMatch(e -> e.from().equals(fk("VA.A")) && e.to().equals(fk("VC.M"))
                        && e.kinds().size() == 2));
    }

    @Test
    void topologicalOrderSatisfiesAllEdgesRootsTerminalsParents() {
        FieldLineageGraph g = diamond();
        TopoResult r = TopologicalOrder.sort(g);
        assertFalse(r.hasCycle());
        assertTrue(r.cycleNodes().isEmpty());
        assertTrue(r.cycleEdges().isEmpty());

        List<FieldKey> order = r.order();
        assertEquals(g.nodes().size(), order.size(), "拓扑序应包含全部节点");
        for (GraphEdge e : g.edges()) {
            int i = order.indexOf(e.from());
            int j = order.indexOf(e.to());
            assertTrue(i >= 0 && j >= 0 && i < j, "拓扑序应满足全部边约束（from 在 to 之前）：" + e);
        }

        assertEquals(set("TA.K", "TB.K"), keys(g.roots()), "根 = 无上游的取数源头");
        assertEquals(set("VD.Z", "VE.Z"), keys(g.terminals()), "终点 = 无下游的最终落点");

        Set<FieldKey> parents = g.parents(fk("VC.M"));
        assertEquals(set("VA.A", "VB.A"), keys(parents), "一跳 parents 只含直接父节点");
        assertFalse(parents.contains(fk("TA.K")), "一跳 parents 不得含两跳外的传递上游 TA.K");
        assertFalse(parents.contains(fk("TB.K")), "一跳 parents 不得含两跳外的传递上游 TB.K");
    }

    @Test
    void topologicalOrderIsReproducible() {
        FieldLineageGraph g = diamond();
        assertEquals(TopologicalOrder.sort(g).order(), TopologicalOrder.sort(g).order(),
                "同输入同输出，拓扑序可复现");
    }

    @Test
    void cycleNodesAreMarkedPreciselyAndEdgesFlaggedOrderNotBroken() {
        FieldLineageGraph g = new FieldLineageGraph();
        edge(g, "VA.X", "VB.X", EdgeKind.DIRECT);
        edge(g, "VB.X", "VC.X", EdgeKind.DIRECT);
        edge(g, "VC.X", "VA.X", EdgeKind.DIRECT);      // 回边，闭合 VA->VB->VC->VA
        edge(g, "VC.X", "VD.X", EdgeKind.AGGREGATE);   // 环的出口，不在环内

        TopoResult r = TopologicalOrder.sort(g);
        assertTrue(r.hasCycle(), "含环图应标 hasCycle");
        assertEquals(set("VA.X", "VB.X", "VC.X"), keys(r.cycleNodes()),
                "环节点仅 VA/VB/VC，出口 VD.X 不得被误标");
        assertEquals(3, r.cycleEdges().size(), "三条环内部边应显式标注");
        assertEquals(4, r.order().size(), "排序不中断，仍输出全部节点");

        GraphEdge exit = null;
        for (GraphEdge e : g.edges()) {
            if (e.to().equals(fk("VD.X"))) {
                exit = e;
            }
        }
        assertNotNull(exit);
        assertTrue(r.order().indexOf(exit.from()) < r.order().indexOf(exit.to()),
                "非环边仍满足拓扑约束");
    }
}
