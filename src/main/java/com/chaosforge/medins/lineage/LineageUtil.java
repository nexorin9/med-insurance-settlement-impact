package com.chaosforge.medins.lineage;

import com.chaosforge.medins.model.EdgeKind;
import com.chaosforge.medins.model.FieldKey;
import com.chaosforge.medins.model.ImpactScope;
import com.chaosforge.medins.model.ListField;
import com.chaosforge.medins.sqlparse.EdgeConfidencePolicy;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 血缘模块内部共用的确定性小工具：边的口径判定、置信度衰减、字段键与分区规范化。
 *
 * <p>所有判定都走固定优先序或契约层 {@link EdgeConfidencePolicy}，
 * 保证「同输入同输出」可复现，且与置信分档单点保持一致。
 */
final class LineageUtil {

    /** 选取逐跳 edge_kind 时的固定优先序（强 → 弱），保证同输入同输出。 */
    private static final List<EdgeKind> KIND_PRIORITY =
            List.of(EdgeKind.DIRECT, EdgeKind.DERIVED, EdgeKind.AGGREGATE, EdgeKind.FILTER);

    private LineageUtil() { }

    /**
     * 为该跳选一个确定性的 edge_kind：严格口径排除 FILTER，保守口径并入；
     * 命中多种 kind 时按固定优先序取其一，且返回值一定是边上真实存在的。
     */
    static EdgeKind pickKind(Set<EdgeKind> kinds, ImpactScope scope) {
        if (kinds == null) {
            return null;
        }
        boolean strict = scope == ImpactScope.STRICT;
        for (EdgeKind k : KIND_PRIORITY) {
            if (!kinds.contains(k)) {
                continue;
            }
            if (strict && !EdgeConfidencePolicy.isStrictEligible(k)) {
                continue;
            }
            return k;
        }
        return null;
    }

    /** 路径上「最弱」的边类型（基础置信度最低者），用于置信度衰减。 */
    static EdgeKind weakestKind(List<PathStep> path) {
        EdgeKind weakest = EdgeKind.DIRECT;
        double min = Double.MAX_VALUE;
        if (path != null) {
            for (PathStep step : path) {
                EdgeKind k = step.kind();
                if (k == null) {
                    continue;
                }
                double base = EdgeConfidencePolicy.baseConfidence(k);
                if (base < min) {
                    min = base;
                    weakest = k;
                }
            }
        }
        return weakest;
    }

    /** 路径上是否含过滤边（口径变化而非取值变化）。 */
    static boolean hasFilter(List<PathStep> path) {
        if (path != null) {
            for (PathStep step : path) {
                if (step.kind() == EdgeKind.FILTER) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 由跳数与最弱边类型算综合置信度（确定性层，模型不确定性置 false）。 */
    static double confidenceOfPath(List<PathStep> path, int hops) {
        EdgeKind weakest = weakestKind(path);
        return EdgeConfidencePolicy.confidenceOf(weakest, Math.max(1, hops), "", false);
    }

    /** 回边字符串：FROM.KEY->TO.KEY，供结果里的 cycleEdges 使用。 */
    static String edgeLabel(FieldKey from, FieldKey to) {
        return from.key() + "->" + to.key();
    }

    /** 去掉限定名前缀，取列名基名（B.FEE_AMT -> FEE_AMT）。 */
    static String baseColumn(String column) {
        if (column == null) {
            return "";
        }
        String s = column.trim().toUpperCase(Locale.ROOT);
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }

    /** 分区展示顺序：基本信息 -> 门诊慢特病 -> 住院诊疗 -> 医疗收费。 */
    static int sectionRank(String section) {
        if (ListField.SECTION_BASIC.equals(section)) {
            return 0;
        }
        if (ListField.SECTION_OUTPATIENT_CHRONIC.equals(section)) {
            return 1;
        }
        if (ListField.SECTION_INPATIENT.equals(section)) {
            return 2;
        }
        if (ListField.SECTION_FEE.equals(section)) {
            return 3;
        }
        return 4;
    }
}
