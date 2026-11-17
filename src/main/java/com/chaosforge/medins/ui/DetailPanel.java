package com.chaosforge.medins.ui;

import com.chaosforge.medins.model.ImpactRecord;
import com.chaosforge.medins.model.ImpactScope;
import com.chaosforge.medins.model.Verdict;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 详情抽屉的内容模型（纯静态方法，不创建任何 AWT/Swing 对象）。
 *
 * <p>把证据链中文短句渲染成逐跳回溯：字段路径、每跳 edge_kind 与累计跳数；
 * 严格/保守口径用不同色标；置信度低于阈值标注「疑似波及，需人工确认」；
 * 判定四档各有呈现，不可判定给出「需人工裁决」入口；待人工核对条目单列一节。
 *
 * <p>证据链输入约定：{@code 视图A.列 --KIND--> 视图B.列 --KIND--> 清单字段}，
 * 其中 KIND 为 DIRECT/DERIVED/AGGREGATE/FILTER，也可用 {@code --[KIND]-->} 写法。
 */
public final class DetailPanel {

    /** 置信度阈值，低于此值标注需人工确认（与 sqlparse 的 EdgeConfidencePolicy 对齐）。 */
    public static final double SUSPECTED_THRESHOLD = 0.65d;

    private static final Pattern HOP =
            Pattern.compile("--\\s*(?:\\[)?\\s*([A-Za-z_]+)\\s*(?:\\])?\\s*-->");

    private DetailPanel() {
    }

    /** 契约固定：把证据链中文短句渲染成逐跳行，首行为完整字段路径。 */
    public static List<String> renderEvidence(String evidenceZh) {
        List<String> lines = new ArrayList<>();
        if (evidenceZh == null || evidenceZh.isBlank()) {
            lines.add("证据链：（未提供）");
            return lines;
        }
        String text = evidenceZh.trim();
        Matcher matcher = HOP.matcher(text);
        List<String> nodes = new ArrayList<>();
        List<String> kinds = new ArrayList<>();
        int cursor = 0;
        while (matcher.find()) {
            nodes.add(text.substring(cursor, matcher.start()).trim());
            kinds.add(matcher.group(1).toUpperCase(Locale.ROOT));
            cursor = matcher.end();
        }
        nodes.add(text.substring(cursor).trim());

        if (nodes.size() < 2 || kinds.isEmpty()) {
            // 没有可识别的逐跳箭头时退化为单行原文，不臆造路径
            lines.add("证据链：" + text);
            return lines;
        }
        lines.add("字段路径：" + String.join(" → ", nodes));
        for (int i = 0; i < kinds.size(); i++) {
            String kind = kinds.get(i);
            lines.add("第 " + (i + 1) + " 跳：" + nodes.get(i) + " → " + nodes.get(i + 1)
                    + " ｜ 边类型：" + kind + "（" + edgeKindZh(kind) + "）"
                    + " ｜ 累计跳数：" + (i + 1));
        }
        return lines;
    }

    /** 严格 / 保守口径不同色标（色值取自 Theme token）。 */
    public static String scopeMark(ImpactScope scope) {
        if (scope == null) {
            return "未标注口径[色标:" + Theme.NEUTRAL + "]";
        }
        return switch (scope) {
            case STRICT -> "严格口径[色标:" + Theme.STRICT_MARK + "]";
            case CONSERVATIVE -> "保守口径[色标:" + Theme.CONSERVATIVE_MARK + "]";
        };
    }

    /** 置信度显著呈现；低于阈值标注「疑似波及，需人工确认」。 */
    public static String confidenceNote(double confidence) {
        String value = String.format(Locale.ROOT, "%.2f", confidence);
        if (confidence < SUSPECTED_THRESHOLD) {
            return "置信度 " + value + "（低于阈值 " + SUSPECTED_THRESHOLD
                    + "）：疑似波及，需人工确认";
        }
        return "置信度 " + value + "（达到阈值）：可作核对依据";
    }

    /** 判定各档对应呈现；不可判定给出「需人工裁决」入口。 */
    public static String verdictPresentation(Verdict verdict) {
        if (verdict == null) {
            return "判定：未归因 ｜ 归因未生成";
        }
        return switch (verdict) {
            case MATERIALLY_AFFECTED -> "判定：实质受影响 ｜ 计入实质影响";
            case SUSPECTED -> "判定：疑似 ｜ 需人工裁决";
            case SAME_NAME_DIFFERENT_MEANING -> "判定：同名不同义 ｜ 不计入实质影响，需人工裁决";
            case UNDETERMINABLE -> "判定：不可判定 ｜ 需人工裁决入口：打开人工裁决";
            case NOT_ATTRIBUTED -> "判定：未归因 ｜ 归因未生成";
        };
    }

    /** 待人工核对条目单列一节；每条自带原因与位置，由调用方传入。 */
    public static String pendingReviewSection(List<String> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 待人工核对条目\n");
        if (items == null || items.isEmpty()) {
            sb.append("- 无（星号取列 / 同名歧义 / 方言不支持等问题会在此列出原因与位置）\n");
            return sb.toString();
        }
        int index = 1;
        for (String item : items) {
            sb.append("- [").append(index++).append("] ").append(item).append("\n");
        }
        return sb.toString();
    }

    /** 综合渲染一行受影响字段的详情：证据链 + 口径 + 置信度 + 判定 + 待核对节。 */
    public static List<String> renderDetail(ImpactRecord record, String evidenceZh,
                                            List<String> reviewItems) {
        List<String> lines = new ArrayList<>();
        lines.addAll(renderEvidence(evidenceZh));
        if (record != null) {
            lines.add("字段：" + record.section() + " / " + record.listFieldName()
                    + " ｜ 来源列：" + record.sourceColumn());
            lines.add(scopeMark(record.scope()));
            lines.add(confidenceNote(record.confidence()));
            lines.add(verdictPresentation(record.verdict()));
        }
        lines.addAll(Arrays.asList(pendingReviewSection(reviewItems).split("\n")));
        return lines;
    }

    private static String edgeKindZh(String kind) {
        return switch (kind) {
            case "DIRECT" -> "直取";
            case "DERIVED" -> "派生";
            case "AGGREGATE" -> "聚合";
            case "FILTER" -> "筛选";
            default -> kind;
        };
    }
}
