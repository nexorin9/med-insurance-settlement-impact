package com.chaosforge.medins.model;

/**
 * 列级边的种类 —— 描述「这一列是从哪一列、以什么方式被读出来的」。
 *
 * <p>四类边与 spec.md §2.1 的 {@code edge_kind} 逐字对应：
 * <ul>
 *   <li>{@link #DIRECT} 直取：输出列直接来自某张表的某列；</li>
 *   <li>{@link #DERIVED} 派生：输出列由 CASE／表达式／函数计算而来；</li>
 *   <li>{@link #AGGREGATE} 聚合：输出列由 SUM／COUNT 等聚合函数产生；</li>
 *   <li>{@link #FILTER} 过滤：出现在 WHERE 条件或 JOIN ON 条件中的列 —— 它改变下游口径但不改变下游取值，
 *       在影响判定中**降一档**，不计入严格口径。</li>
 * </ul>
 */
public enum EdgeKind {

    DIRECT("DIRECT", "直取"),
    DERIVED("DERIVED", "派生"),
    AGGREGATE("AGGREGATE", "聚合"),
    FILTER("FILTER", "过滤");

    private final String wireName;
    private final String labelZh;

    EdgeKind(String wireName, String labelZh) {
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
     * 按报文取值解析；无法识别时返回 {@code null}，由调用方按「不可解析」处理，不静默兜底成某一类。
     */
    public static EdgeKind fromWireName(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        for (EdgeKind kind : values()) {
            if (kind.wireName.equalsIgnoreCase(trimmed)) {
                return kind;
            }
        }
        return null;
    }
}
