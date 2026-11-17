package com.chaosforge.medins.model;

/**
 * 影响面口径 —— 用哪一套规则算出来的受影响集合。
 *
 * <p>口径必须随影响记录一起落库、导出并写进给厂商的说明，否则事后无法判断当初用的是哪一套：
 * <ul>
 *   <li>{@link #STRICT} 严格口径：只沿 DIRECT／DERIVED／AGGREGATE 边扩展；</li>
 *   <li>{@link #CONSERVATIVE} 保守口径：并入 FILTER 边（改筛选条件会改口径但不改取值）。</li>
 * </ul>
 */
public enum ImpactScope {

    STRICT("strict", "严格口径"),
    CONSERVATIVE("conservative", "保守口径");

    private final String wireName;
    private final String labelZh;

    ImpactScope(String wireName, String labelZh) {
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
    public static ImpactScope fromWireName(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        for (ImpactScope scope : values()) {
            if (scope.wireName.equalsIgnoreCase(trimmed)) {
                return scope;
            }
        }
        return null;
    }
}
