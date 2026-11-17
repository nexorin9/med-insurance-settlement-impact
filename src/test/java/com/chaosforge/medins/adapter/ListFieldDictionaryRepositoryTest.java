package com.chaosforge.medins.adapter;

import com.chaosforge.medins.catalog.ListFieldDictionary;
import com.chaosforge.medins.model.ListField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 38：结算清单字段字典的 JDBC 只读读取与同构入口。
 * 用内存 SQLite 作为 HIS 字段元数据表替身，绝不使用真实数据库或网络。
 */
class ListFieldDictionaryRepositoryTest {

    private Connection conn;
    private final ListFieldDictionaryRepository repository = new ListFieldDictionaryRepository();

    @BeforeEach
    void setUp() throws Exception {
        Class.forName("org.sqlite.JDBC");
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE settlement_field_dict ("
                    + "section TEXT, field_name TEXT, field_code TEXT, "
                    + "value_type TEXT, required_flag TEXT, basis_note TEXT)");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (conn != null) {
            conn.close();
        }
    }

    @Test
    void columnMapping_and_twoSourcesSameStructure() throws Exception {
        insert("基本信息", "清单流水号", "B01", "字符", "true", "基本信息口径");
        insert("医疗收费信息", "费用金额", "F01", "数值", "false", "收费口径");

        ListFieldDictionary fromJdbc = repository.load(conn);
        assertEquals(2, fromJdbc.fields().size());
        ListField first = fromJdbc.fields().get(0);
        assertEquals("基本信息", first.section());
        assertEquals("清单流水号", first.fieldName());
        assertEquals("B01", first.fieldCode());
        assertEquals("字符", first.valueType());
        assertTrue(first.requiredFlag());
        assertEquals("基本信息口径", first.basisNote());
        assertFalse(fromJdbc.fields().get(1).requiredFlag());

        // 同一份字典模型从 CSV 与从 JDBC 两条来源产出后结构一致
        String csv = "section,field_name,field_code,value_type,required_flag,basis_note\n"
                + "基本信息,清单流水号,B01,字符,true,基本信息口径\n"
                + "医疗收费信息,费用金额,F01,数值,false,收费口径\n";
        ListFieldDictionary fromCsv = ListFieldDictionary.fromCsv(new StringReader(csv));
        assertEquals(fromCsv.fields(), fromJdbc.fields(), "CSV 与 JDBC 两条来源字段集合与类型完全一致");
    }

    @Test
    void readFailure_orEmptyResult_throws_notEmptyDict() throws Exception {
        // 空表：读失败（字典不可用），不得返回空字典冒充查无字段
        SQLException empty = assertThrows(SQLException.class, () -> repository.load(conn));
        assertTrue(empty.getMessage().contains("字典不可用"));

        // 表不存在：显式抛 SQLException，绝不吞掉
        Connection broken = DriverManager.getConnection("jdbc:sqlite::memory:");
        try {
            assertThrows(SQLException.class, () -> repository.load(broken));
        } finally {
            broken.close();
        }
    }

    @Test
    void sectionDifferences_reportable() {
        // 正确分区项数 31/6/58/98 = 193 → 无差异
        List<ListField> full = new ArrayList<>();
        addRows(full, ListField.SECTION_BASIC, 31);
        addRows(full, ListField.SECTION_OUTPATIENT_CHRONIC, 6);
        addRows(full, ListField.SECTION_INPATIENT, 58);
        addRows(full, ListField.SECTION_FEE, 98);
        assertEquals(193, full.size());
        ListFieldDictionary correct = ListFieldDictionary.fromRows(full);
        assertTrue(ListFieldDictionaryRepository.sectionDifferences(correct).isEmpty());
        assertEquals(31, correct.sectionCounts().get(ListField.SECTION_BASIC));

        // 分区项数不符 → 报出差异明细并要求人工确认执行版本
        List<ListField> wrong = new ArrayList<>();
        wrong.add(new ListField(ListField.SECTION_BASIC, "唯一字段", "X", ListField.VALUE_TYPE_CHAR, true, "口径"));
        List<String> diffs = ListFieldDictionaryRepository.sectionDifferences(
                ListFieldDictionary.fromRows(wrong));
        assertFalse(diffs.isEmpty(), "分区差异必须可报出");
        String joined = String.join("|", diffs);
        assertTrue(joined.contains("基本信息") && joined.contains("期望 31"));
        assertTrue(joined.contains("医疗收费信息") && joined.contains("期望 98"));
        assertTrue(joined.contains("人工确认"));
    }

    private void insert(String section, String name, String code, String type,
                        String required, String note) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO settlement_field_dict(section, field_name, field_code, "
                        + "value_type, required_flag, basis_note) VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, section);
            ps.setString(2, name);
            ps.setString(3, code);
            ps.setString(4, type);
            ps.setString(5, required);
            ps.setString(6, note);
            ps.executeUpdate();
        }
    }

    private static void addRows(List<ListField> out, String section, int count) {
        for (int i = 0; i < count; i++) {
            out.add(new ListField(section, section + "_字段_" + i, "C" + i,
                    ListField.VALUE_TYPE_CHAR, false, "口径"));
        }
    }
}
