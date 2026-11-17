package com.chaosforge.medins.lineage;

import com.chaosforge.medins.model.FieldKey;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 拓扑排序结果：顺序、环内节点、环边、是否有环。 */
public final class TopoResult {

    private final List<FieldKey> order;
    private final Set<FieldKey> cycleNodes;
    private final List<GraphEdge> cycleEdges;
    private final boolean hasCycle;

    TopoResult(List<FieldKey> order, Set<FieldKey> cycleNodes,
               List<GraphEdge> cycleEdges, boolean hasCycle) {
        this.order = List.copyOf(order);
        this.cycleNodes = Collections.unmodifiableSet(new LinkedHashSet<>(cycleNodes));
        this.cycleEdges = List.copyOf(cycleEdges);
        this.hasCycle = hasCycle;
    }

    /** 拓扑序（含全部节点；环内部节点按稳定顺序排入，不中断）。 */
    public List<FieldKey> order() { return order; }

    /** 环内节点（精确：仅位于有向环上的节点）。 */
    public Set<FieldKey> cycleNodes() { return cycleNodes; }

    /** 环内部边（两端都在环内），显式标注。 */
    public List<GraphEdge> cycleEdges() { return cycleEdges; }

    public boolean hasCycle() { return hasCycle; }
}
