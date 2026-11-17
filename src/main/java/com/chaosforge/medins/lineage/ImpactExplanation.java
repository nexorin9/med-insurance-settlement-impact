package com.chaosforge.medins.lineage;

import com.chaosforge.medins.model.FieldKey;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 影响面解释链：为每条受影响字段产出可核对的证据（路径 + 每跳 edge_kind + 跳数 + 口径 + 置信度来源）。
 *
 * <p>结构化对象与中文短句由 {@link Evidence} 同一份结构生成，说明文本与 UI 详情共用，避免两处说法不一致。
 * 证据链每一跳都来自 {@link DownstreamReachability} 的最短路径，其 (from,to,kind) 都能在图中找到对应边。
 */
public final class ImpactExplanation {

    private ImpactExplanation() { }

    /** 为单个受影响字段产出证据链。 */
    public static Evidence evidence(FieldLineageGraph g, ReachabilityResult reach, FieldKey target) {
        FieldKey seed = findSeed(reach);
        List<PathStep> path = reach.paths().getOrDefault(target, List.of());
        int hops = reach.hops().getOrDefault(target, 0);
        double confidence = LineageUtil.confidenceOfPath(path, hops);
        return new Evidence(path, hops, reach.scope(), confidence, seed, target);
    }

    /** 为可达集中每个字段产出证据链，键为字段规范键 VIEW.COLUMN。 */
    public static Map<String, Evidence> evidenceByField(FieldLineageGraph g, ReachabilityResult reach) {
        Map<String, Evidence> out = new LinkedHashMap<>();
        for (FieldKey f : reach.fields()) {
            out.put(f.key(), evidence(g, reach, f));
        }
        return out;
    }

    /** 改动点 = 跳数为 0 的节点（可达结果里 hops 表记录了 seed 的 0 跳）。 */
    private static FieldKey findSeed(ReachabilityResult reach) {
        for (Map.Entry<FieldKey, Integer> e : reach.hops().entrySet()) {
            if (e.getValue() == 0) {
                return e.getKey();
            }
        }
        // 退化情况：无任何下游，返回首个可达字段自身，保证不抛异常
        return reach.fields().isEmpty()
                ? FieldKey.of("UNKNOWN", "SEED")
                : reach.fields().iterator().next();
    }
}
