package com.chaosforge.medins.model;

/**
 * 影响类型 —— 某一栏结算清单字段受影响的性质。
 *
 * <p>{@link #DIRECT} 实质受影响：改动点直接落在该字段的取值链路上；
 * {@link #SUSPECTED} 疑似波及：口径更弱（如经由过滤边）或置信度不足，需人工裁决后才算实质影响。
 */
public enum ImpactType {

    DIRECT("direct", "实质受影响"),
    SUSPECTED("suspected", "疑似波及");

    private final String wireName;
    private final String labelZh;

    ImpactType(String wireName, String labelZh) {
        this.wireName = wireName;
        this.labelZh = labelZh;
    }

    /** 落库、导出与接口报文中的取值。 */
    public String wireName() {
        return wireName;
    }

    /** 中文界面标签。 */
    public String labelZh() {
        return labelZh;
    }

    /**
     * 按报文取值解析；无法识别时返回 {@code null}，由调用方显式报错，不静默兜底。
     */
    public static ImpactType fromWireName(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        for (ImpactType type : values()) {
            if (type.wireName.equalsIgnoreCase(trimmed)) {
                return type;
            }
        }
        return null;
    }
}
