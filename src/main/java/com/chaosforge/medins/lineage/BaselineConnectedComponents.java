package com.chaosforge.medins.lineage;

import com.chaosforge.medins.model.FieldKey;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 基线（仅对比基准用，不进生产路径）：复刻源产品「合并 UPSTREAM + DOWNSTREAM 求无向连通分量」。
 *
 * <p>把每条有向边当无向边，返回 seed 所在连通分量的全部节点（含 seed、上游、下游及旁路节点）。
 * 它会把上游一并框进受影响集，正是本产品 {@link DownstreamReachability} 要避免的；
 * 因此本产品受影响集合恒为基线集合的子集。
 */
public final class BaselineConnectedComponents {

    private BaselineConnectedComponents() { }

    public static Set<FieldKey> component(FieldLineageGraph g, FieldKey seed) {
        Map<FieldKey, LinkedHashSet<FieldKey>> undirected = new HashMap<>();
        for (FieldKey n : g.nodes()) {
            undirected.put(n, new LinkedHashSet<>());
        }
        for (GraphEdge e : g.edges()) {
            undirected.computeIfAbsent(e.from(), k -> new LinkedHashSet<>()).add(e.to());
            undirected.computeIfAbsent(e.to(), k -> new LinkedHashSet<>()).add(e.from());
        }
        LinkedHashSet<FieldKey> component = new LinkedHashSet<>();
        Deque<FieldKey> queue = new ArrayDeque<>();
        component.add(seed);
        queue.add(seed);
        while (!queue.isEmpty()) {
            FieldKey u = queue.poll();
            for (FieldKey v : undirected.getOrDefault(u, new LinkedHashSet<>())) {
                if (component.add(v)) {
                    queue.add(v);
                }
            }
        }
        return component;
    }
}
