package com.chaosforge.medins.lineage;

import com.chaosforge.medins.model.EdgeKind;
import com.chaosforge.medins.model.FieldKey;
import com.chaosforge.medins.model.ImpactScope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 一条影响面证据链：路径 + 每跳 edge_kind + 跳数 + 口径 + 置信度来源。
 *
 * <p>结构化对象（{@link #toMap()} 供 UI 详情抽屉）与中文短句（{@link #zh()} 供厂商说明正文）
 * 由同一份内部数据生成，避免两处说法不一致。每一跳都是图中真实存在的一条边。
 */
public final class Evidence {

    private final List<PathStep> path;
    private final int hops;
    private final ImpactScope scope;
    private final double confidence;
    private final FieldKey seed;
    private final FieldKey target;
    private final String zh;

    Evidence(List<PathStep> path, int hops, ImpactScope scope, double confidence,
             FieldKey seed, FieldKey target) {
        this.path = List.copyOf(path);
        this.hops = hops;
        this.scope = scope == null ? ImpactScope.STRICT : scope;
        this.confidence = confidence;
        this.seed = seed;
        this.target = target;
        this.zh = render();
    }

    /** 逐跳路径（每跳可在图中回溯到对应边）。 */
    public List<PathStep> path() { return path; }

    public int hops() { return hops; }

    public ImpactScope scope() { return scope; }

    /** 综合置信度（来源：EdgeConfidencePolicy，按跳数与最弱边衰减）。 */
    public double confidence() { return confidence; }

    /** 中文短句，含完整路径字段序列、每跳边类型、跳数、口径与置信度来源。 */
    public String zh() { return zh; }

    /** 结构化呈现，与 zh() 同源。 */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seed", seed.key());
        m.put("target", target.key());
        m.put("hops", hops);
        m.put("scope", scope.wireName());
        m.put("scope_label_zh", scope.labelZh());
        m.put("confidence", confidence);
        m.put("confidence_source", "EdgeConfidencePolicy(跳数=" + hops
                + ", 最弱边=" + LineageUtil.weakestKind(path).labelZh() + ")");
        List<Map<String, Object>> steps = new ArrayList<>();
        for (PathStep step : path) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("from", step.from().key());
            sm.put("to", step.to().key());
            sm.put("edge_kind", step.kind().wireName());
            steps.add(sm);
        }
        m.put("path", steps);
        m.put("zh", zh);
        return m;
    }

    private String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("改动点 ").append(seed.key())
                .append(" 经 ").append(hops).append(" 跳（");
        for (int i = 0; i < path.size(); i++) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append(path.get(i).kind().labelZh());
        }
        if (path.isEmpty()) {
            sb.append("无");
        }
        sb.append("）沿").append(scope.labelZh()).append("（").append(scope.wireName())
                .append("）波及 ").append(target.key())
                .append("，路径：").append(seed.key());
        for (PathStep step : path) {
            sb.append(" -(").append(step.kind().wireName()).append(")-> ").append(step.to().key());
        }
        EdgeKind weakest = LineageUtil.weakestKind(path);
        sb.append("；置信度 ").append(String.format(Locale.ROOT, "%.2f", confidence))
                .append("（来源 EdgeConfidencePolicy：跳数 ").append(hops)
                .append("，最弱边 ").append(weakest.labelZh()).append("）");
        return sb.toString();
    }
}
