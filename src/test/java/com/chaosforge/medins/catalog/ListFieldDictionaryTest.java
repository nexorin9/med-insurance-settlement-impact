package com.chaosforge.medins.catalog;

import com.chaosforge.medins.model.ListField;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Task 27 / ListFieldDictionaryTest：字典加载、四分区结构、重复键、取值域强校验、必填优先排序。 */
class ListFieldDictionaryTest {

    private static final String HEADER =
            "section,field_name,field_code,value_type,required_flag,basis_note";

    private static ListFieldDictionary loadReal() throws Exception {
        InputStream in = ListFieldDictionaryTest.class.getClassLoader()
                .getResourceAsStream("samples/settlement_fields.csv");
        assertNotNull(in, "classpath 缺少 samples/settlement_fields.csv");
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return ListFieldDictionary.fromCsv(reader);
        }
    }

    private static int rankOf(String section) {
        return ListFieldDictionary.SECTION_ORDER.indexOf(section);
    }

    @Test
    void loads193RowsWithExactSectionCounts() throws Exception {
        ListFieldDictionary dict = loadReal();

        assertEquals(193, dict.size(), "结算清单字段总数");
        assertEquals(193, dict.fields().size());
        assertEquals(31, dict.sectionCounts().get(ListField.SECTION_BASIC).intValue(), "基本信息");
        assertEquals(6, dict.sectionCounts().get(ListField.SECTION_OUTPATIENT_CHRONIC).intValue(), "门诊慢特病");
        assertEquals(58, dict.sectionCounts().get(ListField.SECTION_INPATIENT).intValue(), "住院诊疗");
        assertEquals(98, dict.sectionCounts().get(ListField.SECTION_FEE).intValue(), "医疗收费");
        assertEquals(4, dict.sectionCounts().size(), "必须恰为四分区");
        assertTrue(SectionScheme.validate(dict).ok(), "四分区结构须与 domain 资产一致");
    }

    @Test
    void fromCsvPathAndReaderProduceSameModel() throws Exception {
        URL url = ListFieldDictionaryTest.class.getClassLoader()
                .getResource("samples/settlement_fields.csv");
        assertNotNull(url);
        Path path = Paths.get(url.toURI());

        ListFieldDictionary byPath = ListFieldDictionary.fromCsv(path);
        ListFieldDictionary byReader = loadReal();
        assertEquals(193, byPath.size());
        assertEquals(byReader.size(), byPath.size());
        assertEquals(byReader.find(ListField.SECTION_FEE, "医疗总费用金额合计"),
                byPath.find(ListField.SECTION_FEE, "医疗总费用金额合计"));
    }

    @Test
    void duplicateKeyReportsBothConflictingRows() {
        String csv = HEADER + "\n"
                + "基本信息,姓名,B06,字符,true,注释A\n"
                + "基本信息,姓名,B06,字符,false,注释B\n";

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ListFieldDictionary.fromCsv(new StringReader(csv)));
        String msg = ex.getMessage();
        assertTrue(msg.contains("重复键"), msg);
        assertTrue(msg.contains("第2行"), "须列出冲突第一行：" + msg);
        assertTrue(msg.contains("第3行"), "须列出冲突第二行：" + msg);
    }

    @Test
    void illegalSectionValueTypeAndRequiredFlagAreRejected() {
        String badSection = HEADER + "\n未知分区,姓名,B06,字符,true,注释\n";
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ListFieldDictionary.fromCsv(new StringReader(badSection)))
                .getMessage().contains("section"));

        String badType = HEADER + "\n基本信息,姓名,B06,整数,true,注释\n";
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ListFieldDictionary.fromCsv(new StringReader(badType)))
                .getMessage().contains("value_type"));

        String badRequired = HEADER + "\n基本信息,姓名,B06,字符,yes,注释\n";
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ListFieldDictionary.fromCsv(new StringReader(badRequired)))
                .getMessage().contains("required_flag"));

        String badColumns = HEADER + "\n基本信息,姓名,B06,字符,true\n";
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ListFieldDictionary.fromCsv(new StringReader(badColumns)))
                .getMessage().contains("列数"));
    }

    @Test
    void findInSectionAndBasisNoteAreConsistent() throws Exception {
        ListFieldDictionary dict = loadReal();

        ListField name = dict.find(ListField.SECTION_BASIC, "姓名").orElseThrow();
        assertEquals("姓名", name.fieldName());
        assertTrue(name.requiredFlag());
        assertFalse(name.basisNote().isBlank(), "basis_note 必须随字段带出");
        assertTrue(dict.find(ListField.SECTION_BASIC, "不存在的字段").isEmpty());
        assertEquals(31, dict.inSection(ListField.SECTION_BASIC).size());
        assertTrue(dict.inSection("不存在的分区").isEmpty());
    }

    @Test
    void sameNameInDifferentSectionsIsNotMerged() {
        ListField a = new ListField(ListField.SECTION_BASIC, "金额合计", "X1", "数值", true, "注释A");
        ListField b = new ListField(ListField.SECTION_FEE, "金额合计", "X2", "数值", false, "注释B");
        ListFieldDictionary dict = ListFieldDictionary.fromRows(List.of(a, b));

        assertEquals(2, dict.size(), "同名不同分区须按 (section+field_name) 分别保留");
        assertNotSame(dict.find(ListField.SECTION_BASIC, "金额合计").orElseThrow(),
                dict.find(ListField.SECTION_FEE, "金额合计").orElseThrow());
        assertEquals(1, dict.sectionCounts().get(ListField.SECTION_BASIC).intValue());
        assertEquals(1, dict.sectionCounts().get(ListField.SECTION_FEE).intValue());
    }

    @Test
    void requiredFirstOrdersRequiredThenSectionStably() throws Exception {
        List<ListField> sorted = loadReal().requiredFirst();
        assertEquals(193, sorted.size());

        boolean optionalSeen = false;
        int prevSectionRank = -1;
        for (ListField f : sorted) {
            if (f.requiredFlag()) {
                assertFalse(optionalSeen, "必填字段必须全部排在可选字段之前");
                int rank = rankOf(f.section());
                assertTrue(rank >= prevSectionRank, "同为必填时按分区顺序稳定排序");
                prevSectionRank = rank;
            } else {
                optionalSeen = true;
            }
        }
        assertTrue(optionalSeen, "字典中既有必填也有可选字段");
    }

    @Test
    void csvSupportsUtf8BomAndRfc4180Quoting() throws Exception {
        String csv = "\uFEFF" + HEADER + "\n"
                + "基本信息,姓名,B06,字符,true,\"含逗号,与\"\"引号\"\"的口径\"\n";

        ListFieldDictionary dict = ListFieldDictionary.fromCsv(new StringReader(csv));
        assertEquals(1, dict.size(), "UTF-8 BOM 不得被当成 section 的一部分");
        ListField f = dict.fields().get(0);
        assertEquals("含逗号,与\"引号\"的口径", f.basisNote(), "须支持引号内逗号与 \"\" 转义");
        assertEquals(ListField.SECTION_BASIC, f.section());
    }

    @Test
    void sectionSchemeReportsExpectedActualAndDifference() {
        ListFieldDictionary partial = ListFieldDictionary.fromRows(List.of(
                new ListField(ListField.SECTION_BASIC, "姓名", "B06", "字符", true, "注释")));

        SectionScheme.Validation v = SectionScheme.validate(partial);
        assertFalse(v.ok());
        String joined = String.join(" | ", v.differences());
        assertTrue(joined.contains("期望值"), joined);
        assertTrue(joined.contains("实际值"), joined);
        assertTrue(joined.contains("差异"), joined);
        assertTrue(joined.contains("31"), "基本信息期望值须出现在差异明细：" + joined);
    }

    @Test
    void fromRowsIsIsomorphicWithCsvSource() throws Exception {
        ListField a = new ListField(ListField.SECTION_BASIC, "姓名", "B06", "字符", true, "注释甲");
        ListField b = new ListField(ListField.SECTION_FEE, "金额合计", "F03", "数值", false, "注释乙");
        String csv = HEADER + "\n"
                + "基本信息,姓名,B06,字符,true,注释甲\n"
                + "医疗收费信息,金额合计,F03,数值,false,注释乙\n";

        ListFieldDictionary fromCsv = ListFieldDictionary.fromCsv(new StringReader(csv));
        ListFieldDictionary fromRows = ListFieldDictionary.fromRows(List.of(a, b));

        assertEquals(fromCsv.size(), fromRows.size(), "JDBC 同构入口与 CSV 产出同一模型");
        assertEquals(fromCsv.find(ListField.SECTION_BASIC, "姓名"),
                fromRows.find(ListField.SECTION_BASIC, "姓名"));
        assertEquals(fromCsv.requiredFirst().get(0), fromRows.requiredFirst().get(0));
    }
}
