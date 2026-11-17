package com.chaosforge.medins.attribution;

import com.chaosforge.medins.model.Verdict;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 归因结构化输出的单点 schema 定义：字段名常量、严格 JSON 结构说明、中文 verdict → 枚举映射。
 *
 * <p>模型响应里的 verdict 用中文四档（实质受影响 / 同名不同义 / 疑似 / 不可判定），
 * 与 {@link Verdict} 枚举的 {@code wireName}（materially_affected 等）不同，
 * 故此处把四档中文映射**写全**，prompt 组装与响应解析共用，避免两处口径漂移。
 * 未归因（not_attributed）不属于模型可输出档位，模型不得返回它。
 */
public final class AttributionSchema {

    public static final String F_LIST_FIELD = "list_field";
    public static final String F_SECTION = "section";
    public static final String F_SOURCE_COLUMN = "source_column";
    public static final String F_VERDICT = "verdict";
    public static final String F_CONFIDENCE = "confidence";
    public static final String F_REASON_ZH = "reason_zh";
    public static final String F_AFFECTED = "affected";
    public static final String F_SUMMARY_ZH = "summary_zh";

    /** 中文 verdict 四档。 */
    public static final String VERDICT_MATERIAL = "实质受影响";
    public static final String VERDICT_SAME_NAME = "同名不同义";
    public static final String VERDICT_SUSPECTED = "疑似";
    public static final String VERDICT_UNDETERMINABLE = "不可判定";

    private static final Map<String, Verdict> ZH_TO_VERDICT;
    private static final Set<String> ZH_NAMES;

    static {
        Map<String, Verdict> m = new LinkedHashMap<>();
        m.put(VERDICT_MATERIAL, Verdict.MATERIALLY_AFFECTED);
        m.put(VERDICT_SAME_NAME, Verdict.SAME_NAME_DIFFERENT_MEANING);
        m.put(VERDICT_SUSPECTED, Verdict.SUSPECTED);
        m.put(VERDICT_UNDETERMINABLE, Verdict.UNDETERMINABLE);
        ZH_TO_VERDICT = Collections.unmodifiableMap(m);
        ZH_NAMES = Collections.unmodifiableSet(new LinkedHashSet<>(m.keySet()));
    }

    private AttributionSchema() {
    }

    /** 中文 verdict → 枚举；无法识别返回 null，由调用方判 schema 不合规，不静默兜底。 */
    public static Verdict verdictFromZh(String raw) {
        if (raw == null) {
            return null;
        }
        return ZH_TO_VERDICT.get(raw.trim());
    }

    public static boolean isValidVerdictZh(String raw) {
        return verdictFromZh(raw) != null;
    }

    /** 允许的四档中文 verdict（不可变）。 */
    public static Set<String> verdictZhNames() {
        return ZH_NAMES;
    }

    /** 严格 JSON 结构片段，供 systemPrompt 与协议断言引用。 */
    public static String schemaHint() {
        return "{\"affected\":[{\"list_field\":...,\"section\":...,\"source_column\":...,"
                + "\"verdict\":\"实质受影响|同名不同义|疑似|不可判定\","
                + "\"confidence\":0..1,\"reason_zh\":...}],\"summary_zh\":...}";
    }
}
