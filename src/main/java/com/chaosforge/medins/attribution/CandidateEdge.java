package com.chaosforge.medins.attribution;

import java.util.Locale;

/**
 * 确定性层算好的候选边 —— 「某取数列可能是某栏清单字段的来源」这一条结构化子图边。
 *
 * <p>归因模型只处理词面不一致的候选边；{@link #exactMatch()} 为真的边（列名精确匹配）
 * 是确定性基线，不询问模型。字段均为结构信息（字段名/列名/表达式/跳数/理由提示），
 * 不含任何患者可识别明细。
 */
public final class CandidateEdge {

    private final String listField;
    private final String section;
    private final String sourceColumn;
    private final String expression;
    private final int hops;
    private final String reasonHint;
    private final boolean exactColumnMatch;

    public CandidateEdge(String listField,
                         String section,
                         String sourceColumn,
                         String expression,
                         int hops,
                         String reasonHint) {
        this(listField, section, sourceColumn, expression, hops, reasonHint, false);
    }

    public CandidateEdge(String listField,
                         String section,
                         String sourceColumn,
                         String expression,
                         int hops,
                         String reasonHint,
                         boolean exactColumnMatch) {
        this.listField = listField == null ? "" : listField.trim();
        this.section = section == null ? "" : section.trim();
        this.sourceColumn = sourceColumn == null ? "" : sourceColumn.trim();
        this.expression = expression == null ? "" : expression.trim();
        this.hops = Math.max(0, hops);
        this.reasonHint = reasonHint == null ? "" : reasonHint.trim();
        this.exactColumnMatch = exactColumnMatch;
    }

    /** 显式标注为「列名精确匹配」的候选边（确定性基线，不调模型）。 */
    public static CandidateEdge exact(String listField,
                                      String section,
                                      String sourceColumn,
                                      String expression,
                                      int hops,
                                      String reasonHint) {
        return new CandidateEdge(listField, section, sourceColumn, expression, hops, reasonHint, true);
    }

    public String listField() {
        return listField;
    }

    public String section() {
        return section;
    }

    public String sourceColumn() {
        return sourceColumn;
    }

    public String expression() {
        return expression;
    }

    public int hops() {
        return hops;
    }

    public String reasonHint() {
        return reasonHint;
    }

    public boolean exactColumnMatch() {
        return exactColumnMatch;
    }

    /** 是否为确定性基线：显式标注或来源列与清单字段词面一致。 */
    public boolean exactMatch() {
        if (exactColumnMatch) {
            return true;
        }
        String a = norm(listField);
        String b = norm(sourceColumn);
        return !a.isEmpty() && a.equals(b);
    }

    private static String norm(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : s.trim().toLowerCase(Locale.ROOT).toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || (c >= '\u4e00' && c <= '\u9fff')) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
