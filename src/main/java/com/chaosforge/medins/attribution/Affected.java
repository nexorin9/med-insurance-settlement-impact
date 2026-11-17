package com.chaosforge.medins.attribution;

import com.chaosforge.medins.model.Verdict;

/**
 * 归因结论中的一条 —— 某栏清单字段的判定、置信度与中文理由。
 *
 * <p>{@link #verdict()} 为四档枚举之一（由中文 verdict 经 {@link AttributionSchema} 映射而来），
 * 置信度落在 [0,1]。不可判定 / 同名不同义不计入实质受影响。
 */
public final class Affected {

    private final String listField;
    private final String section;
    private final String sourceColumn;
    private final Verdict verdict;
    private final double confidence;
    private final String reasonZh;

    public Affected(String listField,
                    String section,
                    String sourceColumn,
                    Verdict verdict,
                    double confidence,
                    String reasonZh) {
        this.listField = listField == null ? "" : listField;
        this.section = section == null ? "" : section;
        this.sourceColumn = sourceColumn == null ? "" : sourceColumn;
        this.verdict = verdict;
        this.confidence = confidence;
        this.reasonZh = reasonZh == null ? "" : reasonZh;
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

    public Verdict verdict() {
        return verdict;
    }

    public double confidence() {
        return confidence;
    }

    public String reasonZh() {
        return reasonZh;
    }
}
