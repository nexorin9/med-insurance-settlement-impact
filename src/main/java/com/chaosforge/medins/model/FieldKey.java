package com.chaosforge.medins.model;

import java.util.Objects;

/**
 * 字段键 ＝ 视图名 + 列名，是本产品贯穿解析、血缘图、影响面记录与台账回写的**统一标识**。
 *
 * <p>规范形式为 {@code VIEW.COLUMN}：两个分量各自去掉首尾空白并转大写。
 * JOIN 两侧同名列以限定名消歧，因此列名允许携带表别名前缀（如 {@code B.FEE_AMT}）。
 * 该类是值对象：不可变、相等性按规范形式判定。
 */
public final class FieldKey {

    private final String view;
    private final String column;

    private FieldKey(String view, String column) {
        this.view = normalize(view);
        this.column = normalize(column);
    }

    /**
     * 由视图名与列名构造字段键。
     *
     * @throws IllegalArgumentException 视图名或列名为空时抛出
     */
    public static FieldKey of(String view, String column) {
        FieldKey key = new FieldKey(view, column);
        if (key.view.isEmpty() || key.column.isEmpty()) {
            throw new IllegalArgumentException("字段键的视图名与列名均不可为空");
        }
        return key;
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase();
    }

    /** 所属视图名（规范形式）。 */
    public String view() {
        return view;
    }

    /** 列名（规范形式，可带限定名前缀）。 */
    public String column() {
        return column;
    }

    /** 规范键字符串 {@code VIEW.COLUMN}。 */
    public String key() {
        return view + "." + column;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FieldKey that)) {
            return false;
        }
        return view.equals(that.view) && column.equals(that.column);
    }

    @Override
    public int hashCode() {
        return Objects.hash(view, column);
    }

    @Override
    public String toString() {
        return key();
    }
}
