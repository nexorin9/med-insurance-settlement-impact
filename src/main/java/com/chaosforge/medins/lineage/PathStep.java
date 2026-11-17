package com.chaosforge.medins.lineage;

import com.chaosforge.medins.model.EdgeKind;
import com.chaosforge.medins.model.FieldKey;

import java.util.Objects;

/** 最短路径上的一跳：from -(kind)-> to；kind 一定是图中该边上真实存在的类型。 */
public final class PathStep {

    private final FieldKey from;
    private final FieldKey to;
    private final EdgeKind kind;

    public PathStep(FieldKey from, FieldKey to, EdgeKind kind) {
        this.from = Objects.requireNonNull(from, "from");
        this.to = Objects.requireNonNull(to, "to");
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public FieldKey from() { return from; }

    public FieldKey to() { return to; }

    public EdgeKind kind() { return kind; }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PathStep that)) {
            return false;
        }
        return from.equals(that.from) && to.equals(that.to) && kind == that.kind;
    }

    @Override
    public int hashCode() {
        return Objects.hash(from, to, kind);
    }

    @Override
    public String toString() {
        return from.key() + "-(" + kind.wireName() + ")->" + to.key();
    }
}
