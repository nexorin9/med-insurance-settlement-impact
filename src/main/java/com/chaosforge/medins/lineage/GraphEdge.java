package com.chaosforge.medins.lineage;

import com.chaosforge.medins.model.EdgeKind;
import com.chaosforge.medins.model.FieldKey;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 一条字段级边：from -> to，边元数据随行 —— 合并后的 edge_kind 集合与来源视图。
 *
 * <p>同一对 (from,to) 的多条不同 edge_kind 边由 {@link #addKind} 合并进同一条边，
 * 去重不丢信息；视图名保留首次出现的作为主视图，全部视图另存于 {@link #views()}。
 */
public final class GraphEdge {

    private final FieldKey from;
    private final FieldKey to;
    private final LinkedHashSet<EdgeKind> kinds = new LinkedHashSet<>();
    private final LinkedHashSet<String> views = new LinkedHashSet<>();
    private final String view;

    /** 由首个 kind 与视图构造。 */
    public GraphEdge(FieldKey from, FieldKey to, EdgeKind kind, String view) {
        this.from = Objects.requireNonNull(from, "from");
        this.to = Objects.requireNonNull(to, "to");
        this.view = view == null ? "" : view;
        this.kinds.add(Objects.requireNonNull(kind, "kind"));
        if (!this.view.isEmpty()) {
            this.views.add(this.view);
        }
    }

    /** 合并同一 (from,to) 的另一种 edge_kind / 来源视图，去重不丢信息。 */
    void addKind(EdgeKind kind, String view) {
        if (kind != null) {
            this.kinds.add(kind);
        }
        if (view != null && !view.isEmpty()) {
            this.views.add(view);
        }
    }

    public FieldKey from() { return from; }

    public FieldKey to() { return to; }

    /** 该边上的全部 edge_kind（不可变副本）。 */
    public Set<EdgeKind> kinds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(kinds));
    }

    /** 主来源视图（首次出现）。 */
    public String view() { return view; }

    /** 附加：该边出现过的全部来源视图（同类辅助信息）。 */
    public Set<String> views() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(views));
    }

    @Override
    public String toString() {
        return from.key() + "->" + to.key() + kinds + "@" + view;
    }
}
