package com.chaosforge.medins.adapter;

import com.chaosforge.medins.catalog.ListFieldDictionary;
import com.chaosforge.medins.model.ListField;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 结算清单字段字典的 JDBC 只读读取（权威数据源之二）。
 *
 * <p>从 HIS 结算模块字段元数据表加载 6 列，映射到与 {@code catalog} 层同构的
 * {@link ListFieldDictionary}（用 {@code fromRows(List<ListField>)}），保证样例 CSV 与院内实读
 * 产出结构一致的字典对象，走同一条代码路径。
 *
 * <p>读失败或空结果一律显式报错并置「字典不可用」，<b>绝不按空字典继续计算影响面</b>
 * （否则会得出「没有字段受影响」的假结论）。分区项数按 31/6/58/98 = 193 核对，差异给出明细。
 */
public final class ListFieldDictionaryRepository {

    public static final String DEFAULT_TABLE = "settlement_field_dict";
    public static final String SELECT_SQL =
            "select section, field_name, field_code, value_type, required_flag, basis_note from "
                    + DEFAULT_TABLE;

    /** 四分区目标项数（以目标统筹区执行的版本为准）。 */
    public static final Map<String, Integer> EXPECTED_COUNTS = expected();

    private static Map<String, Integer> expected() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put(ListField.SECTION_BASIC, 31);
        m.put(ListField.SECTION_OUTPATIENT_CHRONIC, 6);
        m.put(ListField.SECTION_INPATIENT, 58);
        m.put(ListField.SECTION_FEE, 98);
        return Map.copyOf(m);
    }

    public ListFieldDictionary load(Connection conn) throws SQLException {
        Objects.requireNonNull(conn, "conn");
        List<ListField> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(SELECT_SQL);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(new ListField(
                        rs.getString("section"),
                        rs.getString("field_name"),
                        rs.getString("field_code"),
                        rs.getString("value_type"),
                        parseRequired(rs.getObject("required_flag")),
                        rs.getString("basis_note")));
            }
        }
        // 空结果同样是「字典不可用」，不返回空字典冒充查无字段
        if (rows.isEmpty()) {
            throw new SQLException("结算清单字段字典不可用：只读查询未返回任何字段，禁止按空字典计算影响面");
        }
        return ListFieldDictionary.fromRows(rows);
    }

    /** 核对分区项数，返回差异明细（空列表表示一致，须人工确认执行版本）。 */
    public static List<String> sectionDifferences(ListFieldDictionary dict) {
        Map<String, Integer> actual = dict == null ? Map.of() : dict.sectionCounts();
        List<String> diffs = new ArrayList<>();
        for (Map.Entry<String, Integer> e : EXPECTED_COUNTS.entrySet()) {
            int a = actual.getOrDefault(e.getKey(), 0);
            if (a != e.getValue()) {
                diffs.add(e.getKey() + " 期望 " + e.getValue() + " 实际 " + a
                        + "（差 " + (a - e.getValue()) + "）；须人工确认字段字典执行版本");
            }
        }
        return diffs;
    }

    public static Map<String, Integer> expectedCounts() {
        return EXPECTED_COUNTS;
    }

    private static boolean parseRequired(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.intValue() != 0;
        }
        String s = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return s.equals("true") || s.equals("1") || s.equals("y") || s.equals("yes");
    }
}
