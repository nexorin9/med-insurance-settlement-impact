package com.chaosforge.medins.lineage;

import com.chaosforge.medins.model.EdgeKind;
import com.chaosforge.medins.model.FieldKey;
import com.chaosforge.medins.model.ImpactScope;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static com.chaosforge.medins.lineage.LineageFixtures.edge;
import static com.chaosforge.medins.lineage.LineageFixtures.fk;
import static com.chaosforge.medins.lineage.LineageFixtures.keys;
import static com.chaosforge.medins.lineage.LineageFixtures.set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task24（深度验收 c）：12 段视图 × 8 表规模图上的正确性与耗时上限、环/非环场景。 */
class LineageScaleTest {

    private static final FieldKey SEED = fk("V0.C0");
    private static final int VIEWS = 12;
    private static final int TABLES = 8;

    /** 12 段视图 × 8 张底层表的字段级图；withCycle 决定是否在 C0 链尾补一条回边。 */
    private FieldLineageGraph scaleGraph(boolean withCycle) {
        FieldLineageGraph g = new FieldLineageGraph();
        // 每个视图的 8 个输出列各来自一张底层表列
        for (int v = 0; v < VIEWS; v++) {
            for (int t = 0; t < TABLES; t++) {
                edge(g, "T" + t + ".C" + t, "V" + v + ".C" + t, EdgeKind.DIRECT);
            }
        }
        // 跨视图消费链：V(v).C_t -> V(v+1).C_t
        for (int v = 0; v < VIEWS - 1; v++) {
            for (int t = 0; t < TABLES; t++) {
                edge(g, "V" + v + ".C" + t, "V" + (v + 1) + ".C" + t, EdgeKind.DIRECT);
            }
        }
        // 过滤分支：保证保守口径比严格口径多
        edge(g, "V0.C0", "V0.F", EdgeKind.FILTER);
        edge(g, "V0.F", "V0.FD", EdgeKind.DIRECT);
        // 环：C0 链尾 V11.C0 回指 V9.C0
        if (withCycle) {
            edge(g, "V11.C0", "V9.C0", EdgeKind.DIRECT);
        }
        return g;
    }

    /** 人工确定的期望下游链：V1.C0 .. V11.C0，共 11 个，不含上游与 seed 本身。 */
    private Set<String> expectedChain() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (int v = 1; v < VIEWS; v++) {
            out.add("V" + v + ".C0");
        }
        return out;
    }

    @Test
    void scaleGraphStructureAndReachableSetAreComplete() {
        FieldLineageGraph g = scaleGraph(false);
        assertTrue(g.nodes().size() >= VIEWS * TABLES, "节点数应达 12×8 规模");
        assertTrue(g.edgeCount() >= VIEWS * TABLES, "边数应达规模");

        ReachabilityResult strict = DownstreamReachability.reachable(g, SEED, ImpactScope.STRICT);
        assertEquals(expectedChain(), keys(strict.fields()),
                "可达集合须包含全部真正下游且与人工基准一致");
        assertEquals(VIEWS - 1, strict.fields().size());
        assertEquals(VIEWS - 1, strict.maxHops());
        assertTrue(strict.fields().contains(fk("V11.C0")), "链尾不得遗漏");
        assertFalse(strict.fields().contains(fk("T0.C0")), "上游不得框入");
        assertFalse(strict.fields().contains(SEED), "seed 不算受影响字段");

        ReachabilityResult cons = DownstreamReachability.reachable(g, SEED, ImpactScope.CONSERVATIVE);
        assertTrue(cons.fields().containsAll(strict.fields()));
        assertTrue(cons.fields().contains(fk("V0.F")));
        assertTrue(cons.fields().contains(fk("V0.FD")));
        assertEquals(set("V0.F", "V0.FD"), keys(DualModeDiff.extraConservativeFields(g, SEED)));
    }

    @Test
    void singleReachabilityStaysUnder500MillisAndPrintsStats() {
        FieldLineageGraph g = scaleGraph(false);
        int runs = 5;
        long strictTotal = 0;
        long consTotal = 0;
        long maxNs = 0;
        for (int k = 0; k < runs; k++) {
            long t0 = System.nanoTime();
            DownstreamReachability.reachable(g, SEED, ImpactScope.STRICT);
            long d0 = System.nanoTime() - t0;
            strictTotal += d0;
            maxNs = Math.max(maxNs, d0);

            long t1 = System.nanoTime();
            DownstreamReachability.reachable(g, SEED, ImpactScope.CONSERVATIVE);
            long d1 = System.nanoTime() - t1;
            consTotal += d1;
            maxNs = Math.max(maxNs, d1);
        }
        double avgStrictMs = strictTotal / (double) runs / 1_000_000.0d;
        double avgConsMs = consTotal / (double) runs / 1_000_000.0d;
        double maxMs = maxNs / 1_000_000.0d;

        System.out.printf("LineageScaleTest 节点数=%d 边数=%d 严格平均耗时=%.3fms 保守平均耗时=%.3fms 单次最大=%.3fms%n",
                g.nodes().size(), g.edgeCount(), avgStrictMs, avgConsMs, maxMs);

        assertTrue(maxMs <= 500.0d, "单次可达集遍历须 ≤ 500ms，实际 " + maxMs + "ms");
        assertTrue(g.nodes().size() > 0 && g.edgeCount() > 0);
    }

    @Test
    void cycleAndNonCycleScaleGraphsAreBothHandled() {
        FieldLineageGraph noCycle = scaleGraph(false);
        FieldLineageGraph withCycle = scaleGraph(true);

        ReachabilityResult plain = DownstreamReachability.reachable(noCycle, SEED, ImpactScope.STRICT);
        assertFalse(plain.containsCycle(), "非环规模图不应误报环");
        assertTrue(plain.cycleEdges().isEmpty());
        assertTrue(plain.fields().contains(fk("V11.C0")), "非环场景遍历须完成");

        ReachabilityResult cyclic = DownstreamReachability.reachable(withCycle, SEED, ImpactScope.CONSERVATIVE);
        assertTrue(cyclic.containsCycle(), "含环规模图须正确标记 containsCycle");
        assertFalse(cyclic.cycleEdges().isEmpty(), "环边须显式标注");
        assertTrue(cyclic.cycleEdges().contains("V11.C0->V9.C0"),
                "回边应指向链上祖先，实际：" + cyclic.cycleEdges());
        assertEquals(expectedChain(), keys(DownstreamReachability.reachable(
                withCycle, SEED, ImpactScope.STRICT).fields()),
                "含环图的严格可达集合仍与基准一致，环内节点不重复计数");
        assertTrue(cyclic.fields().contains(fk("V11.C0")), "含环场景遍历须完成且不崩");
    }
}
