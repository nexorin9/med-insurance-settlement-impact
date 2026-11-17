package com.chaosforge.medins.lineage;

import com.chaosforge.medins.model.EdgeKind;
import com.chaosforge.medins.model.FieldKey;
import com.chaosforge.medins.model.ImpactScope;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 沿 DOWNSTREAM 的 BFS 下游可达子集：只回答「改这一列到底波及了谁」，明确不把上游框入。
 *
 * <p>这是与源产品「无向连通分量」的实质差异点。严格口径只走 DIRECT / DERIVED / AGGREGATE，
 * 保守口径并入 FILTER。visited 在入队时登记，环内节点不重复展开、可达集合无重复元素；
 * 结果用 LinkedHashSet 保序，同一 seed 多次调用同序可复现。
 *
 * <p>环以「指向 BFS 树祖先（含 seed 与自身）的回边」识别，写入 cycleEdges 并置 containsCycle，
 * 兄弟节点之间的横叉边（菱形依赖）不算环，避免误报。
 */
public final class DownstreamReachability {

    private DownstreamReachability() { }

    public static ReachabilityResult reachable(FieldLineageGraph graph, FieldKey seed, ImpactScope scope) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(seed, "seed");
        ImpactScope s = scope == null ? ImpactScope.STRICT : scope;

        LinkedHashSet<FieldKey> fields = new LinkedHashSet<>();       // 结果集（不含 seed），BFS 入队顺序
        Map<FieldKey, Integer> hops = new LinkedHashMap<>();
        Map<FieldKey, List<PathStep>> paths = new LinkedHashMap<>();
        Set<FieldKey> visited = new HashSet<>();
        List<String> cycleEdges = new ArrayList<>();
        boolean containsCycle = false;

        visited.add(seed);
        hops.put(seed, 0);
        paths.put(seed, new ArrayList<>());
        Deque<FieldKey> queue = new ArrayDeque<>();
        queue.add(seed);

        while (!queue.isEmpty()) {
            FieldKey u = queue.poll();
            Set<FieldKey> ancestors = ancestorsOf(seed, paths.get(u));
            // graph.downstream(u) 是稳定有序集合，保证 BFS 顺序确定
            for (FieldKey v : graph.downstream(u)) {
                Set<EdgeKind> kinds = graph.kindsOf(u, v);
                EdgeKind kind = LineageUtil.pickKind(kinds, s);
                if (kind == null) {
                    continue; // 该边在此口径下不可扩展（严格口径排除纯 FILTER 边）
                }
                if (visited.contains(v)) {
                    // 指向已在路径上的祖先才是回边；菱形/横叉边不算环
                    if (ancestors.contains(v)) {
                        containsCycle = true;
                        String label = LineageUtil.edgeLabel(u, v);
                        if (!cycleEdges.contains(label)) {
                            cycleEdges.add(label);
                        }
                    }
                    continue; // 已登记的不再入队，环内节点不重复展开
                }
                visited.add(v); // 入队时登记
                int h = hops.get(u) + 1;
                hops.put(v, h);
                List<PathStep> path = new ArrayList<>(paths.get(u));
                path.add(new PathStep(u, v, kind));
                paths.put(v, path);
                fields.add(v);
                queue.add(v);
            }
        }

        return new ReachabilityResult(fields, hops, paths, s, containsCycle, cycleEdges);
    }

    /** 严格口径下的下游可达字段集合。 */
    public static Set<FieldKey> strictFields(FieldLineageGraph graph, FieldKey seed) {
        return reachable(graph, seed, ImpactScope.STRICT).fields();
    }

    /** 最短路径上的全部祖先节点（含 seed 与 u 自身），用于判回边。 */
    private static Set<FieldKey> ancestorsOf(FieldKey seed, List<PathStep> path) {
        Set<FieldKey> out = new HashSet<>();
        out.add(seed);
        if (path != null) {
            for (PathStep step : path) {
                out.add(step.to());
            }
        }
        return out;
    }
}
