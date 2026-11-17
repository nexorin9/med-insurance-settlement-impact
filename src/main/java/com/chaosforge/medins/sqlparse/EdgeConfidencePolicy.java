package com.chaosforge.medins.sqlparse;

import com.chaosforge.medins.model.EdgeKind;

/** 置信分档单点：FILTER 边降一档且不计入严格口径，其余三类同档。 */
public final class EdgeConfidencePolicy {

    /** 疑似波及阈值：低于该值只进「疑似波及」，不进实质影响。 */
    public static final double SUSPECTED_THRESHOLD = 0.65d;

    private EdgeConfidencePolicy() { }

    /** 该类边是否参与严格口径的下游可达扩展。 */
    public static boolean isStrictEligible(EdgeKind kind) {
        return kind != null && kind != EdgeKind.FILTER;
    }

    /** 分档：直取/派生/聚合为 1 档，过滤为 2 档。 */
    public static int rank(EdgeKind kind) {
        return kind == EdgeKind.FILTER ? 2 : 1;
    }

    /** 基础置信度：直取 1.0 / 派生 0.9 / 聚合 0.85 / 过滤 0.5（外连接再降）。 */
    public static double baseConfidence(EdgeKind kind) {
        if (kind == null) return 0.5d;
        return switch (kind) {
            case DIRECT -> 1.0d;
            case DERIVED -> 0.9d;
            case AGGREGATE -> 0.85d;
            case FILTER -> 0.5d;
        };
    }

    /** 综合置信度：基础分 × 跳数衰减 × 外连接衰减，结果落在 [0,1]。 */
    public static double confidenceOf(EdgeKind kind, int hops, String joinType, boolean modelUncertain) {
        double value = baseConfidence(kind);
        int h = Math.max(1, hops);
        value = value * Math.pow(0.88d, h - 1);
        if (joinType != null && !joinType.isBlank() && !"inner".equalsIgnoreCase(joinType)) {
            value *= 0.8d;
        }
        if (modelUncertain) value *= 0.6d;
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    public static double confidenceOf(ColumnEdge edge) {
        return confidenceOf(edge.edgeKind(), 1, edge.joinType(), edge.ambiguous());
    }

    /** 跳数 ≥ 3 的受影响字段需人工确认，不自动判定实质影响。 */
    public static boolean needsHumanConfirmation(int hops) {
        return hops >= 3;
    }
}
