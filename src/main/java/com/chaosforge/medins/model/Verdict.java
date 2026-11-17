package com.chaosforge.medins.model;

/**
 * 归因判定 —— 某一列与某一栏清单字段**是不是真的同义**。
 *
 * <p>由模型（{@code attribution} 模块）对词面不一致的候选边给出，确定性层（列名精确匹配）直接落到
 * {@link #MATERIALLY_AFFECTED}，不询问模型。判定四档中的「同名不同义」与「不可判定」**不计入实质受影响**，
 * 一律进入需人工裁决清单。
 */
public enum Verdict {

    NOT_ATTRIBUTED("not_attributed", "未归因"),
    MATERIALLY_AFFECTED("materially_affected", "实质受影响"),
    SAME_NAME_DIFFERENT_MEANING("same_name_different_meaning", "同名不同义"),
    SUSPECTED("suspected", "疑似"),
    UNDETERMINABLE("undetermined", "不可判定");

    private final String wireName;
    private final String labelZh;

    Verdict(String wireName, String labelZh) {
        this.wireName = wireName;
        this.labelZh = labelZh;
    }

    /** 落库、导出与接口报文中的取值。 */
    public String wireName() {
        return wireName;
    }

    /** 中文界面标签（直接出现在给厂商的说明里）。 */
    public String labelZh() {
        return labelZh;
    }

    /** 是否计入实质受影响；同名不同义与不可判定一律不算。 */
    public boolean countsAsMaterialImpact() {
        return this == MATERIALLY_AFFECTED;
    }

    /** 是否需要人工裁决（同名不同义 / 疑似 / 不可判定）。 */
    public boolean needsHumanArbitration() {
        return this == SAME_NAME_DIFFERENT_MEANING || this == SUSPECTED || this == UNDETERMINABLE;
    }

    /**
     * 按报文取值解析；无法识别时返回 {@code null}，由调用方判为不合规响应，不静默兜底。
     */
    public static Verdict fromWireName(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        for (Verdict verdict : values()) {
            if (verdict.wireName.equalsIgnoreCase(trimmed)) {
                return verdict;
            }
        }
        return null;
    }
}
