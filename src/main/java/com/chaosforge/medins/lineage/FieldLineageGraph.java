package com.chaosforge.medins.lineage;

import com.chaosforge.medins.model.EdgeKind;
import com.chaosforge.medins.model.FieldKey;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 字段级血缘图：双方向邻接表（UPSTREAM / DOWNSTREAM）。
 *
 * <p>节点是 {@link FieldKey}（规范形式 VIEW.COLUMN，大写）；JOIN 两侧同名列以限定名前缀消歧，
 * 不同视图的同名列天然是不同节点，不误合并。同一对 (from,to) 是一条边，多重 edge_kind
 * 合并进同一条边，去重不丢信息。所有容器用 LinkedHash* 保插入顺序，
 * 保证构图、遍历、导出「同输入同输出」可复现。
 *
 * <p>语义：upstream(key) = 直接汇入 key 的父节点（一跳）；downstream(key) = key 直接指向的消费节点（一跳）。
 * roots() 为无上游的取数源头，terminals() 为无下游的最终落点。
 */
public final class FieldLineageGraph {

    /** 边的身份：有序 (from,to)。 */
    private record EdgeId(FieldKey from, FieldKey to) { }

    /** 全部节点，稳定插入顺序。 */
    private final LinkedHashSet<FieldKey> nodeOrder = new LinkedHashSet<>();
    private final Map<FieldKey, LinkedHashSet<FieldKey>> upstream = new LinkedHashMap<>();
    private final Map<FieldKey, LinkedHashSet<FieldKey>> downstream = new LinkedHashMap<>();
    private final Map<EdgeId, GraphEdge> edgeMap = new LinkedHashMap<>();

    /** 增加一条列级边；同一 (from,to) 的多种 edge_kind 合并进同一条边。 */
    public void addEdge(FieldKey from, FieldKey to, EdgeKind kind, String view) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(kind, "kind");
        String v = view == null ? "" : view;

        nodeOrder.add(from);
        nodeOrder.add(to);
        // 保证每个节点在两张邻接表里都有条目（可能为空集合）
        upstream.computeIfAbsent(from, k -> new LinkedHashSet<>());
        upstream.computeIfAbsent(to, k -> new LinkedHashSet<>());
        downstream.computeIfAbsent(from, k -> new LinkedHashSet<>());
        downstream.computeIfAbsent(to, k -> new LinkedHashSet<>());

        upstream.get(to).add(from);
        downstream.get(from).add(to);

        EdgeId id = new EdgeId(from, to);
        GraphEdge edge = edgeMap.get(id);
        if (edge == null) {
            edgeMap.put(id, new GraphEdge(from, to, kind, v));
        } else {
            edge.addKind(kind, v);
        }
    }

    /** 一跳：直接汇入 key 的父节点集合（非传递闭包）。 */
    public Set<FieldKey> upstream(FieldKey key) {
        return copy(upstream.get(key));
    }

    /** 一跳：key 直接指向的消费节点集合。 */
    public Set<FieldKey> downstream(FieldKey key) {
        return copy(downstream.get(key));
    }

    /** 一跳 parents，语义等同 upstream（源产品的一跳语义，非传递闭包）。 */
    public Set<FieldKey> parents(FieldKey key) {
        return upstream(key);
    }

    /** 根节点：没有上游的取数源头。 */
    public Set<FieldKey> roots() {
        LinkedHashSet<FieldKey> out = new LinkedHashSet<>();
        for (FieldKey n : nodeOrder) {
            Set<FieldKey> ups = upstream.get(n);
            if (ups == null || ups.isEmpty()) {
                out.add(n);
            }
        }
        return out;
    }

    /** 终点：没有下游的最终落点。 */
    public Set<FieldKey> terminals() {
        LinkedHashSet<FieldKey> out = new LinkedHashSet<>();
        for (FieldKey n : nodeOrder) {
            Set<FieldKey> downs = downstream.get(n);
            if (downs == null || downs.isEmpty()) {
                out.add(n);
            }
        }
        return out;
    }

    /** 全部节点，稳定顺序（不可变副本）。 */
    public Set<FieldKey> nodes() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(nodeOrder));
    }

    /** 去重后的边条数（按 (from,to) 计）。 */
    public int edgeCount() {
        return edgeMap.size();
    }

    /** 全部边，稳定顺序。 */
    public List<GraphEdge> edges() {
        return List.copyOf(edgeMap.values());
    }

    /** 某条边上的全部 edge_kind；无此边时返回空集合。 */
    public Set<EdgeKind> kindsOf(FieldKey from, FieldKey to) {
        GraphEdge e = edgeMap.get(new EdgeId(from, to));
        return e == null ? Collections.emptySet() : e.kinds();
    }

    private static Set<FieldKey> copy(Set<FieldKey> src) {
        return src == null ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(src));
    }
}
