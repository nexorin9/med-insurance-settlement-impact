package com.chaosforge.medins.lineage;

import com.chaosforge.medins.model.FieldKey;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Kahn 拓扑排序 + 环检测。
 *
 * <p>先用「从 v 出发能否沿下游回到 v」精确判定环内节点（不是 Kahn 残余节点，
 * 避免把环的下游尾巴误标为环节点）；剔除环内部边后剩余子图必为 DAG，
 * 再对其做稳定 Kahn 排序，输出包含全部节点（排序不中断）。环内部边作为 cycleEdges 显式标注。
 * 容器全部有序，同输入同输出可复现。
 */
public final class TopologicalOrder {

    private TopologicalOrder() { }

    public static TopoResult sort(FieldLineageGraph graph) {
        // 1. 精确环内节点
        LinkedHashSet<FieldKey> cycleNodes = new LinkedHashSet<>();
        for (FieldKey n : graph.nodes()) {
            if (onCycle(graph, n)) {
                cycleNodes.add(n);
            }
        }

        // 2. 剔除环内部边（两端都在环内），剩余子图是 DAG
        List<GraphEdge> dagEdges = new ArrayList<>();
        List<GraphEdge> cycleEdges = new ArrayList<>();
        for (GraphEdge e : graph.edges()) {
            if (cycleNodes.contains(e.from()) && cycleNodes.contains(e.to())) {
                cycleEdges.add(e);
            } else {
                dagEdges.add(e);
            }
        }

        // 3. 稳定 Kahn（按 nodes() 顺序扫描，结果可复现）
        Map<FieldKey, LinkedHashSet<FieldKey>> succ = new LinkedHashMap<>();
        Map<FieldKey, Integer> indeg = new LinkedHashMap<>();
        for (FieldKey n : graph.nodes()) {
            succ.put(n, new LinkedHashSet<>());
            indeg.put(n, 0);
        }
        for (GraphEdge e : dagEdges) {
            succ.get(e.from()).add(e.to());
            indeg.merge(e.to(), 1, Integer::sum);
        }

        List<FieldKey> nodes = new ArrayList<>(graph.nodes());
        List<FieldKey> order = new ArrayList<>();
        Set<FieldKey> emitted = new HashSet<>();
        while (order.size() < nodes.size()) {
            boolean progress = false;
            for (FieldKey n : nodes) {
                if (emitted.contains(n) || indeg.getOrDefault(n, 0) != 0) {
                    continue;
                }
                emitted.add(n);
                order.add(n);
                progress = true;
                for (FieldKey m : succ.getOrDefault(n, new LinkedHashSet<>())) {
                    indeg.merge(m, -1, Integer::sum);
                }
            }
            if (!progress) {
                // 理论上 DAG 必有入度 0 节点；兜底按稳定顺序补齐，保证排序不中断
                for (FieldKey n : nodes) {
                    if (!emitted.contains(n)) {
                        emitted.add(n);
                        order.add(n);
                    }
                }
                break;
            }
        }

        return new TopoResult(order, cycleNodes, cycleEdges, !cycleNodes.isEmpty());
    }

    /** v 是否在某个有向环上：从 v 的后继 BFS，若能回到 v 则成立。 */
    private static boolean onCycle(FieldLineageGraph graph, FieldKey v) {
        Set<FieldKey> visited = new HashSet<>();
        Deque<FieldKey> queue = new ArrayDeque<>();
        for (FieldKey w : graph.downstream(v)) {
            if (visited.add(w)) {
                queue.add(w);
            }
        }
        while (!queue.isEmpty()) {
            FieldKey u = queue.poll();
            if (u.equals(v)) {
                return true;
            }
            for (FieldKey w : graph.downstream(u)) {
                if (visited.add(w)) {
                    queue.add(w);
                }
            }
        }
        return false;
    }
}
