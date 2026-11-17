package com.chaosforge.medins.lineage;

import com.chaosforge.medins.model.EdgeKind;
import com.chaosforge.medins.model.FieldKey;
import com.chaosforge.medins.model.ImpactScope;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.chaosforge.medins.lineage.LineageFixtures.edge;
import static com.chaosforge.medins.lineage.LineageFixtures.fk;
import static com.chaosforge.medins.lineage.LineageFixtures.keys;
import static com.chaosforge.medins.lineage.LineageFixtures.ordered;
import static com.chaosforge.medins.lineage.LineageFixtures.set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task20：跳数分层、最短路径逐跳还原、跳数 ≥ 3 标记人工确认、同跳数稳定排序。 */
class HopLayerTest {

    private static final FieldKey SEED = fk("VA.C");

    private FieldLineageGraph graph() {
        FieldLineageGraph g = new FieldLineageGraph();
        edge(g, "VA.C", "VB.C", EdgeKind.DIRECT);
        edge(g, "VB.C", "VC.C", EdgeKind.DERIVED);
        edge(g, "VC.C", "VD.C", EdgeKind.AGGREGATE);
        edge(g, "VD.C", "VE.C", EdgeKind.DIRECT);
        edge(g, "VA.C", "VB2.C", EdgeKind.DIRECT);   // 同为一跳的分支，用于验证稳定排序
        return g;
    }

    @Test
    void hopLayersAndMaxHopsAreCorrect() {
        FieldLineageGraph g = graph();
        ReachabilityResult r = DownstreamReachability.reachable(g, SEED, ImpactScope.STRICT);
        assertEquals(1, r.hops().get(fk("VB.C")).intValue());
        assertEquals(1, r.hops().get(fk("VB2.C")).intValue());
        assertEquals(2, r.hops().get(fk("VC.C")).intValue());
        assertEquals(3, r.hops().get(fk("VD.C")).intValue());
        assertEquals(4, r.hops().get(fk("VE.C")).intValue());
        assertEquals(4, r.maxHops());
        assertEquals(ImpactScope.STRICT, r.scope());
    }

    @Test
    void fieldsAtHopsThreeOrMoreNeedHumanConfirmation() {
        FieldLineageGraph g = graph();
        ReachabilityResult r = DownstreamReachability.reachable(g, SEED, ImpactScope.STRICT);
        assertEquals(set("VD.C", "VE.C"), keys(r.needsHumanConfirmation()),
                "跳数 ≥ 3 的字段需人工确认");
        assertFalse(r.needsHumanConfirmation(fk("VB.C")));
        assertFalse(r.needsHumanConfirmation(fk("VC.C")));
        assertTrue(r.needsHumanConfirmation(fk("VD.C")));
        assertTrue(r.needsHumanConfirmation(fk("VE.C")));
    }

    @Test
    void shortestPathIsRestorableHopByHop() {
        FieldLineageGraph g = graph();
        ReachabilityResult r = DownstreamReachability.reachable(g, SEED, ImpactScope.STRICT);
        for (FieldKey f : r.fields()) {
            List<PathStep> path = r.paths().get(f);
            assertEquals(r.hops().get(f).intValue(), path.size(), "路径逐跳数应等于 hops：" + f.key());
            assertEquals(SEED, path.get(0).from(), "首跳起点应是 seed");
            assertEquals(f, path.get(path.size() - 1).to(), "末跳终点应是该字段");
            for (PathStep step : path) {
                assertTrue(g.kindsOf(step.from(), step.to()).contains(step.kind()),
                        "每跳都能回溯到图中的边：" + step);
            }
        }
        // 具体链路：VA.C -(直取)-> VB.C -(派生)-> VC.C -(聚合)-> VD.C -(直取)-> VE.C
        List<EdgeKind> kinds = new ArrayList<>();
        for (PathStep s : r.paths().get(fk("VE.C"))) {
            kinds.add(s.kind());
        }
        assertEquals(List.of(EdgeKind.DIRECT, EdgeKind.DERIVED, EdgeKind.AGGREGATE, EdgeKind.DIRECT),
                kinds, "最短路径应逐跳还原每跳 edge_kind");
    }

    @Test
    void sameHopFieldsHaveStableOrderAcrossCalls() {
        FieldLineageGraph g = graph();
        ReachabilityResult a = DownstreamReachability.reachable(g, SEED, ImpactScope.STRICT);
        ReachabilityResult b = DownstreamReachability.reachable(g, SEED, ImpactScope.STRICT);
        assertEquals(ordered(a.fields()), ordered(b.fields()), "同跳数内顺序稳定、可复现");
        List<String> order = ordered(a.fields());
        assertTrue(order.indexOf("VB.C") < order.indexOf("VB2.C"),
                "同为一跳的字段按邻接插入顺序稳定排列");
        assertTrue(order.indexOf("VB2.C") < order.indexOf("VC.C"),
                "一跳字段整体排在二跳字段之前");
    }
}
