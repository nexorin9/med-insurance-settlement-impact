package com.chaosforge.medins.attribution;

import com.chaosforge.medins.model.Verdict;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 归因结果 —— 严格 schema 校验后的模型输出，或降级占位结果。
 *
 * <p>{@link #fromJson(String)} 严格校验：非 JSON、缺字段、非法 verdict、越界 confidence
 * 一律判 {@link ModelErrorCode#MODEL_SCHEMA_INVALID}，不做「尽力解析」、不静默兜底。
 * verdict 中文四档经 {@link AttributionSchema} 写全映射到 {@link Verdict} 枚举。
 */
public final class AttributionResult {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<Affected> affected;
    private final String summaryZh;
    private final boolean degraded;

    private AttributionResult(List<Affected> affected, String summaryZh, boolean degraded) {
        this.affected = List.copyOf(affected);
        this.summaryZh = summaryZh == null ? "" : summaryZh;
        this.degraded = degraded;
    }

    /** 严格解析模型输出的 JSON（affected[] + summary_zh）。 */
    public static AttributionResult fromJson(String json) throws ModelGatewayException {
        if (json == null || json.isBlank()) {
            throw invalid("响应为空或非 JSON");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new ModelGatewayException(ModelErrorCode.MODEL_SCHEMA_INVALID,
                    "模型响应不是合法 JSON", e);
        }
        if (root == null || !root.isObject()) {
            throw invalid("根节点必须是对象");
        }
        JsonNode affectedNode = root.get(AttributionSchema.F_AFFECTED);
        if (affectedNode == null || !affectedNode.isArray()) {
            throw invalid("缺少 affected 数组");
        }
        JsonNode summaryNode = root.get(AttributionSchema.F_SUMMARY_ZH);
        if (summaryNode == null || !summaryNode.isTextual()) {
            throw invalid("缺少 summary_zh 文本字段");
        }

        List<Affected> parsed = new ArrayList<>();
        int idx = 0;
        for (JsonNode a : affectedNode) {
            idx++;
            if (!a.isObject()) {
                throw invalid("第" + idx + "条 affected 不是对象");
            }
            String listField = requireText(a, AttributionSchema.F_LIST_FIELD, idx);
            String section = requireText(a, AttributionSchema.F_SECTION, idx);
            String sourceColumn = requireText(a, AttributionSchema.F_SOURCE_COLUMN, idx);
            String verdictZh = requireText(a, AttributionSchema.F_VERDICT, idx);
            String reasonZh = requireText(a, AttributionSchema.F_REASON_ZH, idx);

            Verdict verdict = AttributionSchema.verdictFromZh(verdictZh);
            if (verdict == null) {
                throw invalid("第" + idx + "条 affected verdict 非法：" + verdictZh);
            }
            JsonNode confNode = a.get(AttributionSchema.F_CONFIDENCE);
            if (confNode == null || !confNode.isNumber()) {
                throw invalid("第" + idx + "条 affected confidence 缺失或非数值");
            }
            double confidence = confNode.doubleValue();
            if (Double.isNaN(confidence) || confidence < 0.0d || confidence > 1.0d) {
                throw invalid("第" + idx + "条 affected confidence 越界：" + confidence);
            }
            parsed.add(new Affected(listField, section, sourceColumn, verdict, confidence, reasonZh));
        }
        return new AttributionResult(parsed, summaryNode.asText(), false);
    }

    /** 正常结果构造（供确定性基线与网关合并结果使用）。 */
    public static AttributionResult of(List<Affected> affected, String summaryZh) {
        return new AttributionResult(affected == null ? List.of() : affected, summaryZh, false);
    }

    /** 降级占位结果：归因未生成，affected 为空，禁止回写。 */
    public static AttributionResult degraded(String summaryZh) {
        return new AttributionResult(List.of(), summaryZh, true);
    }

    public List<Affected> affected() {
        return affected;
    }

    public String summaryZh() {
        return summaryZh;
    }

    public boolean degraded() {
        return degraded;
    }

    // ---------- 内部 ----------

    private static String requireText(JsonNode node, String field, int idx) throws ModelGatewayException {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw invalid("第" + idx + "条 affected 缺少文本字段 " + field);
        }
        return value.asText();
    }

    private static ModelGatewayException invalid(String reason) {
        return new ModelGatewayException(ModelErrorCode.MODEL_SCHEMA_INVALID,
                "模型响应不符合归因 schema：" + reason);
    }
}
