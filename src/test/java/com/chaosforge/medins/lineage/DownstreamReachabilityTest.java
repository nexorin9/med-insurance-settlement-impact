package com.chaosforge.medins.lineage;

import com.chaosforge.medins.model.EdgeKind;
import com.chaosforge.medins.model.FieldKey;
import com.chaosforge.medins.model.ImpactScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.chaosforge.medins.lineage.LineageFixtures.edge;
import static com.chaosforge.medins.lineage.LineageFixtures.fk;
import static com.chaosforge.medins.lineage.LineageFixtures.keys;
import static com.chaosforge.medins.lineage.LineageFixtures.ordered;
import static com.chaosforge.medins.lineage.LineageFixtures.set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task18：沿 DOWNSTREAM 的严格口径可达子集、不框入上游、结果同序可复现。 */
class DownstreamReachabilityTest {

    /** seed=V.S，含上游 SRC.U、严格链 D1->D2、纯过滤边 F->FD。 */
    private FieldLineageGraph graph() {
        FieldLineageGraph g = new FieldLineageGraph();
        edge(g, "SRC.U", "V.S", EdgeKind.DIRECT);      // 上游
        edge(g, "V.S", "V.D1", EdgeKind.DIRECT);
        edge(g, "V.D1", "V.D2", EdgeKind.DERIVED);
        edge(g, "V.S", "V.F", EdgeKind.FILTER);        // 纯过滤边
        edge(g, "V.F", "V.FD", EdgeKind.DIRECT);
        return g;
    }

    private static final FieldKey SEED = fk("V.S");

    @Test
    void reachableSetIsDownstreamOnlyAndExcludesUpstream() {
        FieldLineageGraph g = graph();
        ReachabilityResult r = DownstreamReachability.reachable(g, SEED, ImpactScope.STRICT);
        assertEquals(set("V.D1", "V.D2"), keys(r.fields()), "严格口径只含真正下游");
        assertFalse(r.fields().contains(fk("SRC.U")), "上游字段不得被框入受影响集");
        assertFalse(r.fields().contains(SEED), "seed 改动点本身不算受影响字段");

        // 与基线的关键区别：无向连通分量会把上游一并框入
        Set<FieldKey> baseline = BaselineConnectedComponents.component(g, SEED);
        assertTrue(baseline.contains(fk("SRC.U")), "基线会把上游框入，本产品不会");
        assertTrue(baseline.containsAll(r.fields()), "本产品集合应为基线集合的子集");
    }

    @Test
    void strictSkipsFilterOnlyEdges() {
        FieldLineageGraph g = graph();
        ReachabilityResult strict = DownstreamReachability.reachable(g, SEED, ImpactScope.STRICT);
        assertFalse(strict.fields().contains(fk("V.F")));
        assertFalse(strict.fields().contains(fk("V.FD")));
        assertEquals(DownstreamReachability.strictFields(g, SEED), strict.fields(),
                "strictFields 便捷入口应与严格可达一致");
    }

    @Test
    void conservativeIncludesFilterEdges() {
        FieldLineageGraph g = graph();
        ReachabilityResult cons = DownstreamReachability.reachable(g, SEED, ImpactScope.CONSERVATIVE);
        assertEquals(set("V.D1", "V.D2", "V.F", "V.FD"), keys(cons.fields()));
        assertEquals(ImpactScope.CONSERVATIVE, cons.scope());
    }

    @Test
    void repeatedCallsReturnSameOrder() {
        FieldLineageGraph g = graph();
        ReachabilityResult a = DownstreamReachability.reachable(g, SEED, ImpactScope.CONSERVATIVE);
        ReachabilityResult b = DownstreamReachability.reachable(g, SEED, ImpactScope.CONSERVATIVE);
        assertEquals(ordered(a.fields()), ordered(b.fields()), "同 seed 多次调用同序可复现");
    }

    @Test
    void hopsAndShortestPathsAreAvailable() {
        FieldLineageGraph g = graph();
        ReachabilityResult r = DownstreamReachability.reachable(g, SEED, ImpactScope.STRICT);
        assertEquals(1, r.hops().get(fk("V.D1")).intValue());
        assertEquals(2, r.hops().get(fk("V.D2")).intValue());
        assertEquals(2, r.maxHops());
        List<PathStep> path = r.paths().get(fk("V.D2"));
        assertEquals(2, path.size(), "最短路径逐跳数与 hops 一致");
        assertEquals(SEED, path.get(0).from());
        assertEquals(fk("V.D2"), path.get(path.size() - 1).to());
        assertFalse(r.containsCycle());
        assertTrue(r.cycleEdges().isEmpty());
    }
}
