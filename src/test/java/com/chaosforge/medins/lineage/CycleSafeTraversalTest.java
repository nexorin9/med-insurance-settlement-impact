package com.chaosforge.medins.lineage;

import com.chaosforge.medins.model.EdgeKind;
import com.chaosforge.medins.model.FieldKey;
import com.chaosforge.medins.model.ImpactScope;
import org.junit.jupiter.api.Test;

import static com.chaosforge.medins.lineage.LineageFixtures.edge;
import static com.chaosforge.medins.lineage.LineageFixtures.fk;
import static com.chaosforge.medins.lineage.LineageFixtures.keys;
import static com.chaosforge.medins.lineage.LineageFixtures.ordered;
import static com.chaosforge.medins.lineage.LineageFixtures.set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task21：环安全遍历、回边标注、containsCycle、菱形横叉边不误报为环。 */
class CycleSafeTraversalTest {

    private static final FieldKey SEED = fk("VA.X");

    /** 三节点环 VA->VB->VC->VA，外加环的出口 VD.X。 */
    private FieldLineageGraph cycleGraph() {
        FieldLineageGraph g = new FieldLineageGraph();
        edge(g, "VA.X", "VB.X", EdgeKind.DIRECT);
        edge(g, "VB.X", "VC.X", EdgeKind.DIRECT);
        edge(g, "VC.X", "VA.X", EdgeKind.DIRECT);      // 回边
        edge(g, "VC.X", "VD.X", EdgeKind.AGGREGATE);   // 环的出口
        return g;
    }

    /** 菱形依赖（无环）：VA 分叉到 VB/VC，再汇合到 VD。 */
    private FieldLineageGraph diamondGraph() {
        FieldLineageGraph g = new FieldLineageGraph();
        edge(g, "VA.X", "VB.X", EdgeKind.DIRECT);
        edge(g, "VA.X", "VC.X", EdgeKind.DIRECT);
        edge(g, "VB.X", "VD.X", EdgeKind.DIRECT);
        edge(g, "VC.X", "VD.X", EdgeKind.AGGREGATE);
        return g;
    }

    @Test
    void cycleTraversalDoesNotBreakAndHasNoDuplicateFields() {
        FieldLineageGraph g = cycleGraph();
        ReachabilityResult r = DownstreamReachability.reachable(g, SEED, ImpactScope.STRICT);
        assertEquals(set("VB.X", "VC.X", "VD.X"), keys(r.fields()),
                "环内节点不重复展开，可达集合无重复元素");
        assertEquals(3, r.fields().size());
        assertEquals(3, r.hops().get(fk("VD.X")).intValue());
        assertEquals(3, r.maxHops());
    }

    @Test
    void cycleBackEdgesAreFlaggedAndContainsCycleTrue() {
        FieldLineageGraph g = cycleGraph();
        ReachabilityResult r = DownstreamReachability.reachable(g, SEED, ImpactScope.STRICT);
        assertTrue(r.containsCycle(), "环场景应标 containsCycle=true");
        assertFalse(r.cycleEdges().isEmpty(), "cycleEdges 非空");
        assertTrue(r.cycleEdges().contains("VC.X->VA.X"),
                "回边应指向已在路径上的祖先 VC.X->VA.X，实际：" + r.cycleEdges());
    }

    @Test
    void repeatedCycleTraversalIsStableAndNoGrowth() {
        FieldLineageGraph g = cycleGraph();
        ReachabilityResult a = DownstreamReachability.reachable(g, SEED, ImpactScope.STRICT);
        ReachabilityResult b = DownstreamReachability.reachable(g, SEED, ImpactScope.STRICT);
        ReachabilityResult c = DownstreamReachability.reachable(g, SEED, ImpactScope.STRICT);
        assertEquals(ordered(a.fields()), ordered(b.fields()));
        assertEquals(ordered(b.fields()), ordered(c.fields()), "环场景下多次遍历不重复计数");
    }

    @Test
    void diamondCrossEdgeIsNotMistakenForCycle() {
        FieldLineageGraph g = diamondGraph();
        ReachabilityResult r = DownstreamReachability.reachable(g, SEED, ImpactScope.STRICT);
        assertEquals(set("VB.X", "VC.X", "VD.X"), keys(r.fields()));
        assertFalse(r.containsCycle(), "菱形汇合是横叉边，不是回边，不得误报为环");
        assertTrue(r.cycleEdges().isEmpty(), "无环场景 cycleEdges 应为空");
    }
}
