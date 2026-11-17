package com.chaosforge.medins.attribution;

import com.chaosforge.medins.model.ChangeKind;
import com.chaosforge.medins.model.ImpactRecord;
import com.chaosforge.medins.model.ImpactStatus;
import com.chaosforge.medins.model.ImpactType;
import com.chaosforge.medins.model.Verdict;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 归因业务层 —— 只依赖 {@link ModelGateway} 接口与本模块数据结构，provider-neutral。
 *
 * <p>职责：
 * <ul>
 *   <li>确定性基线：列名精确匹配的候选边不问模型，模型只处理词面不一致的边；</li>
 *   <li>{@link #merge}：把 verdict / confidence / reason_zh 并回影响面，四档归位；</li>
 *   <li>{@link #degrade}：模型不可达/超时/schema 失败时置 DEGRADED 并标「归因未生成」；</li>
 *   <li>{@link #writeBackEnabled}：回写闸门（降级时显式关闭）；</li>
 *   <li>{@link #draftVendorNote}：生成中文说明草稿（未确认、不自动进回写体、批次占位）。</li>
 * </ul>
 * 模型不得越过人工闸门：merge / degrade 都不会把记录推进到 CONFIRMED / WRITTEN_BACK，
 * 已由人工确认的记录在降级时保持状态不变。
 */
public final class AttributionService {

    /** 样例批次一律用此占位，禁止编造真实批次号。 */
    public static final String EXAMPLE_BATCH = "<EXAMPLE_BATCH>";

    /** 降级标注（界面/导出/说明文本同步可见）。 */
    public static final String DEGRADED_MARK = "归因未生成";

    private AttributionService() {
    }

    /**
     * 确定性基线 + 模型归因的业务入口：业务层只依赖 {@link ModelGateway} 接口。
     * 列名精确匹配的候选边直接落「实质受影响」，不触发模型调用；只有词面不一致的边交给模型。
     */
    public static AttributionResult attribute(ModelGateway gateway, AttributionRequest request)
            throws ModelGatewayException {
        if (request == null) {
            throw new ModelGatewayException(ModelErrorCode.MODEL_SCHEMA_INVALID, "归因请求不可为 null");
        }
        List<Affected> deterministic = new ArrayList<>();
        List<CandidateEdge> modelEdges = new ArrayList<>();
        for (CandidateEdge c : request.candidates()) {
            if (c.exactMatch()) {
                deterministic.add(new Affected(c.listField(), c.section(), c.sourceColumn(),
                        Verdict.MATERIALLY_AFFECTED, 1.0d,
                        "列名精确匹配，确定性基线成立，未调用模型"));
            } else {
                modelEdges.add(c);
            }
        }
        if (modelEdges.isEmpty()) {
            // 全部为确定性基线：不询问模型（省钱且可复现）。
            return AttributionResult.of(deterministic, "全部候选边为列名精确匹配，未触发模型调用");
        }
        AttributionRequest effective = request.toBuilder().candidates(modelEdges).build();
        AttributionResult modelResult = gateway.attribute(effective);
        List<Affected> merged = new ArrayList<>(deterministic);
        merged.addAll(modelResult.affected());
        return AttributionResult.of(merged, modelResult.summaryZh());
    }

    /**
     * 归因结果并入影响面：verdict / confidence 覆盖到对应记录，四档归位。
     * 不可判定 / 同名不同义不计入实质受影响（impact=SUSPECTED），并进需人工裁决清单、附 reason_zh。
     */
    public static List<ImpactRecord> merge(List<ImpactRecord> records, AttributionResult result) {
        if (records == null) {
            return List.of();
        }
        if (result == null || result.degraded()) {
            // 降级结果无模型结论，不并入，保持原记录（业务层随后走 degrade）。
            return List.copyOf(records);
        }
        Map<String, Affected> byFull = new HashMap<>();
        Map<String, Affected> byField = new HashMap<>();
        for (Affected a : result.affected()) {
            byFull.putIfAbsent(key(a.listField(), a.sourceColumn()), a);
            byField.putIfAbsent(a.listField().trim(), a);
        }

        List<ImpactRecord> out = new ArrayList<>();
        for (ImpactRecord r : records) {
            Affected a = byFull.get(key(r.listFieldName(), r.sourceColumn()));
            if (a == null) {
                a = byFull.get(key(r.listFieldName(), ""));
            }
            if (a == null) {
                a = byField.get(r.listFieldName());
            }
            if (a == null) {
                out.add(r);
                continue;
            }
            Verdict v = a.verdict() == null ? Verdict.NOT_ATTRIBUTED : a.verdict();
            // 四档归位：只有「实质受影响」计入 DIRECT；其余一律疑似、不计实质影响。
            ImpactType impact = v == Verdict.MATERIALLY_AFFECTED ? ImpactType.DIRECT : ImpactType.SUSPECTED;
            ImpactStatus status = r.status();
            if (!r.status().isHumanConfirmed()) {
                if (v == Verdict.SAME_NAME_DIFFERENT_MEANING || v == Verdict.UNDETERMINABLE) {
                    status = ImpactStatus.AMBIGUOUS; // 进需人工裁决清单
                } else {
                    status = ImpactStatus.ATTRIBUTED;
                }
            }
            out.add(r.toBuilder()
                    .verdict(v)
                    .confidence(a.confidence())
                    .reasonZh(a.reasonZh())
                    .impact(impact)
                    .status(status)
                    .build());
        }
        return List.copyOf(out);
    }

    /** 需人工裁决清单：同名不同义 / 疑似 / 不可判定的记录。 */
    public static List<ImpactRecord> humanArbitration(List<ImpactRecord> records) {
        List<ImpactRecord> out = new ArrayList<>();
        if (records != null) {
            for (ImpactRecord r : records) {
                if (r.verdict() != null && r.verdict().needsHumanArbitration()) {
                    out.add(r);
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * 降级：模型不可达/超时/schema 失败时使用。非终态记录置 DEGRADED、标「归因未生成」；
     * 已由人工确认/回写的记录状态保持不变（模型无权推翻人工结论，也无权推进状态）。
     */
    public static List<ImpactRecord> degrade(List<ImpactRecord> records) {
        if (records == null) {
            return List.of();
        }
        List<ImpactRecord> out = new ArrayList<>();
        for (ImpactRecord r : records) {
            if (r.status().isHumanConfirmed()) {
                out.add(r);
                continue;
            }
            String reason = r.reasonZh();
            if (!reason.contains(DEGRADED_MARK)) {
                reason = reason.isBlank() ? DEGRADED_MARK : reason + "；" + DEGRADED_MARK;
            }
            out.add(r.toBuilder()
                    .status(ImpactStatus.DEGRADED)
                    .verdict(Verdict.NOT_ATTRIBUTED)
                    .reasonZh(reason)
                    .build());
        }
        return List.copyOf(out);
    }

    /** 回写闸门：降级结果显式关闭回写。 */
    public static boolean writeBackEnabled(AttributionResult result) {
        return result != null && !result.degraded();
    }

    /** 综合回写闸门：降级结果或任一条 DEGRADED 记录都拒绝回写（服务层拦截）。 */
    public static boolean writeBackEnabled(List<ImpactRecord> records, AttributionResult result) {
        if (!writeBackEnabled(result)) {
            return false;
        }
        if (records != null) {
            for (ImpactRecord r : records) {
                if (r.status() == ImpactStatus.DEGRADED) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 厂商说明草稿（中文，可直接粘贴）。结构 = 改动点 + 受影响清单字段 + 批次占位 + 逐条理由。
     * 明确标为「未确认草稿」，不得自动进入回写请求体；批次一律 {@link #EXAMPLE_BATCH} 占位。
     */
    public static String draftVendorNote(List<ImpactRecord> records, String viewName, String changedField) {
        StringBuilder sb = new StringBuilder();
        sb.append("【厂商说明 · 未确认草稿】本内容为未确认草稿，不得自动进入回写请求体；"
                + "回写只接受人工确认后的版本。").append('\n');

        sb.append("一、改动点").append('\n');
        sb.append("  视图：").append(nz(viewName)).append('\n');
        sb.append("  改动列：").append(nz(changedField)).append('\n');
        ChangeKind kind = (records != null && !records.isEmpty()) ? records.get(0).changeKind() : null;
        sb.append("  改动类型：").append(kind == null ? "未登记" : kind.labelZh()).append('\n');
        sb.append("  改动说明：以专员在工具内登记的 change_note_zh 为准（本草稿不臆造、不编造患者信息）。").append('\n');

        sb.append("二、上报批次：").append(EXAMPLE_BATCH).append("（占位，禁止编造真实批次号）").append('\n');

        sb.append("三、受影响清单字段").append('\n');
        if (records == null || records.isEmpty()) {
            sb.append("  （无）").append('\n');
        } else {
            int i = 1;
            for (ImpactRecord r : records) {
                sb.append("  [").append(i++).append("] 分区=").append(nz(r.section()))
                        .append("；字段=").append(nz(r.listFieldName()))
                        .append("；来源列=").append(nz(r.sourceColumn()))
                        .append("；判定=").append(r.verdict() == null ? "未归因" : r.verdict().labelZh())
                        .append("；置信度=").append(String.format(Locale.ROOT, "%.2f", r.confidence()))
                        .append("；口径=").append(r.scope() == null ? "严格口径" : r.scope().labelZh())
                        .append('\n');
            }
        }

        sb.append("四、需人工裁决").append('\n');
        List<ImpactRecord> arbitration = humanArbitration(records);
        if (arbitration.isEmpty()) {
            sb.append("  （无）").append('\n');
        } else {
            for (ImpactRecord r : arbitration) {
                sb.append("  - ").append(nz(r.listFieldName()))
                        .append("：").append(r.verdict() == null ? "" : r.verdict().labelZh())
                        .append("，理由：").append(nz(r.reasonZh())).append('\n');
            }
        }

        sb.append("五、逐条理由").append('\n');
        if (records != null) {
            for (ImpactRecord r : records) {
                sb.append("  - ").append(nz(r.listFieldName())).append("：").append(nz(r.reasonZh())).append('\n');
            }
        }
        return sb.toString();
    }

    // ---------- 内部 ----------

    private static String key(String listField, String sourceColumn) {
        return nz(listField) + "\u0001" + nz(sourceColumn);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
