package com.chaosforge.medins.attribution;

import com.chaosforge.medins.model.ChangeKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 归因 prompt 组装 —— 输入是确定性层算好的候选边结构化子图 + 人工改动说明，不含患者可识别明细。
 *
 * <p>模型只干一件事：判断「这一列和这一栏清单字段是不是真的同义」并给出理由。
 * 输出钉死为严格 JSON，四档 verdict 与 confidence 取值域由 {@link AttributionSchema} 单点定义。
 */
public final class AttributionPrompt {

    private AttributionPrompt() {
    }

    /** 组装结构化子图 + 改动说明（change_kind / change_note_zh），返回可序列化为 JSON 的 Map。 */
    public static Map<String, Object> build(AttributionRequest req) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("view_name", req.viewName());
        ChangeKind kind = req.changeKind();
        root.put("change_kind", kind == null ? "" : kind.wireName());
        root.put("change_kind_label_zh", kind == null ? "" : kind.labelZh());
        root.put("change_note_zh", req.changeNoteZh());

        List<Map<String, Object>> candidates = new ArrayList<>();
        for (CandidateEdge c : req.candidates()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("list_field", c.listField());
            item.put("section", c.section());
            item.put("source_column", c.sourceColumn());
            item.put("expression", c.expression());
            item.put("hops", c.hops());
            item.put("reason_hint", c.reasonHint());
            candidates.add(item);
        }
        root.put("candidates", candidates);
        return root;
    }

    /** 系统提示词：要求输出严格 JSON，逐字钉死字段与四档 verdict。 */
    public static String systemPrompt() {
        return "你是医保结算清单取数影响分析的语义归因助手。任务：对每一条候选边，"
                + "判断 source_column（取数列）与 list_field（结算清单字段）是否真的同义、是否实质受影响，"
                + "并给出简短中文理由。只输出严格 JSON，不要输出 JSON 以外的任何文字，不要使用 Markdown 代码块。"
                + "输出结构必须为：" + AttributionSchema.schemaHint() + "。"
                + "其中 affected 是数组，每条必须含 list_field、section、source_column、verdict、confidence、reason_zh 六个字段；"
                + "顶层必须含 summary_zh 字段。verdict 取值严格限定为四档："
                + AttributionSchema.VERDICT_MATERIAL + " / "
                + AttributionSchema.VERDICT_SAME_NAME + " / "
                + AttributionSchema.VERDICT_SUSPECTED + " / "
                + AttributionSchema.VERDICT_UNDETERMINABLE + "；"
                + "confidence 为 0 到 1 之间的数值（含 0 和 1）。"
                + "同名不同义与不可判定不计入实质受影响。严禁输出患者姓名、证件号等可识别明细，"
                + "也不要请求或回显任何凭据。";
    }
}
