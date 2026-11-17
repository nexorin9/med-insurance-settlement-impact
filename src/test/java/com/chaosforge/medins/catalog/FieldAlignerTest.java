package com.chaosforge.medins.catalog;

import com.chaosforge.medins.catalog.AlignmentResult.Group;
import com.chaosforge.medins.model.ListField;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Task 28 / FieldAlignerTest：三级对齐顺序、一对多歧义拒绝、同名不同分区不误合并、四类分组。 */
class FieldAlignerTest {

    private static ListFieldDictionary loadReal() throws Exception {
        InputStream in = FieldAlignerTest.class.getClassLoader()
                .getResourceAsStream("samples/settlement_fields.csv");
        assertNotNull(in);
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return ListFieldDictionary.fromCsv(reader);
        }
    }

    @Test
    void exactMatchIsPreferredOverAlias() {
        ListFieldDictionary dict = ListFieldDictionary.fromRows(List.of(
                new ListField(ListField.SECTION_BASIC, "fee_amt", "X1", "数值", true, "注释1"),
                new ListField(ListField.SECTION_BASIC, "清单流水号", "X2", "字符", false, "注释2")));
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        aliases.put("fee_amt", List.of("清单流水号")); // 别名本可命中另一字段
        FieldAligner aligner = new FieldAligner(aliases);

        AlignmentResult r = aligner.align("FEE_AMT", dict);

        assertEquals(Group.EXACT, r.group(), "第一级命中须优先于第二级别名");
        assertEquals("fee_amt", r.matches().get(0).fieldName());
        assertFalse(r.ambiguous());
        assertTrue(r.hitTrail().get(0).contains("①"), "命中轨迹须记录第一级");
        assertTrue(r.reasonZh().contains("无需询问模型"));
    }

    @Test
    void builtinAliasTableHitsFeeAmtAndPatientId() throws Exception {
        ListFieldDictionary dict = loadReal();
        FieldAligner aligner = new FieldAligner();

        assertTrue(aligner.aliasTable().containsKey("fee_amt"), "别名表键为小写归一列名");
        assertTrue(aligner.aliasTable().containsKey("patient_id"));

        AlignmentResult fee = aligner.align("fee_amt", dict);
        assertEquals(Group.ALIAS, fee.group());
        assertEquals("医疗总费用金额合计", fee.matches().get(0).fieldName());
        assertTrue(fee.reasonZh().contains("优先于模型"));

        AlignmentResult pid = aligner.align("patient_id", dict);
        assertEquals(Group.ALIAS, pid.group());
        assertEquals("医保个人编号", pid.matches().get(0).fieldName());
        assertTrue(pid.hitTrail().get(1).contains("②"));
    }

    @Test
    void oneToManyAmbiguityIsRejected() {
        ListFieldDictionary dict = ListFieldDictionary.fromRows(List.of(
                new ListField(ListField.SECTION_FEE, "甲类费用合计", "F04", "数值", false, "注释"),
                new ListField(ListField.SECTION_FEE, "乙类费用合计", "F05", "数值", false, "注释"),
                new ListField(ListField.SECTION_FEE, "自费费用合计", "F06", "数值", false, "注释")));
        FieldAligner aligner = new FieldAligner(Map.of("mix_fee", List.of("费用合计")));

        AlignmentResult r = aligner.align("MIX_FEE", dict);

        assertEquals(Group.AMBIGUOUS, r.group(), "某列对多个清单字段须拒绝自动合并");
        assertTrue(r.ambiguous());
        assertEquals(3, r.matches().size());
        assertTrue(r.reasonZh().contains("歧义"));
    }

    @Test
    void sameNameDifferentSectionIsNotMerged() {
        ListFieldDictionary dict = ListFieldDictionary.fromRows(List.of(
                new ListField(ListField.SECTION_BASIC, "金额合计", "X1", "数值", true, "注释"),
                new ListField(ListField.SECTION_FEE, "金额合计", "X2", "数值", false, "注释")));

        AlignmentResult r = new FieldAligner().align("金额合计", dict);

        assertEquals(Group.AMBIGUOUS, r.group(), "同名不同分区精确命中多个字段，拒绝自动合并");
        assertEquals(2, r.matches().size());
        assertTrue(r.matches().stream().anyMatch(f -> f.section().equals(ListField.SECTION_BASIC)));
        assertTrue(r.matches().stream().anyMatch(f -> f.section().equals(ListField.SECTION_FEE)));
    }

    @Test
    void unknownColumnFallsThroughToModelLevel() throws Exception {
        AlignmentResult r = new FieldAligner().align("zzz_totally_unknown", loadReal());

        assertEquals(Group.MODEL, r.group(), "词面不一致交模型判定");
        assertTrue(r.matches().isEmpty());
        assertFalse(r.ambiguous());
        assertEquals(3, r.hitTrail().size(), "三级命中情况都要记录，不隐藏降级来源");
        assertTrue(r.hitTrail().get(0).contains("①"));
        assertTrue(r.hitTrail().get(1).contains("②"));
        assertTrue(r.hitTrail().get(2).contains("③"));
    }

    @Test
    void aliasHitPrecedesModelJudgement() throws Exception {
        FieldAligner aligner = new FieldAligner(Map.of("weird_col", List.of("金额合计")));

        AlignmentResult r = aligner.align("WEIRD_COL", loadReal());

        assertEquals(Group.ALIAS, r.group(), "别名命中优先于模型判定");
        assertTrue(r.hitTrail().get(1).contains("②"));
    }

    @Test
    void resultsAreGroupedIntoFourClasses() throws Exception {
        Map<Group, List<AlignmentResult>> grouped = new FieldAligner()
                .alignAll(List.of("fee_amt", "patient_id", "zzz_unknown"), loadReal());

        assertEquals(4, grouped.size(), "四类分组键都要出现，供 UI 与导出呈现");
        for (Group g : Group.values()) {
            assertNotNull(grouped.get(g), "缺少分组：" + g);
        }
        assertEquals(2, grouped.get(Group.ALIAS).size());
        assertEquals(1, grouped.get(Group.MODEL).size());
    }

    @Test
    void multipleColumnsToSameFieldAreAmbiguous() throws Exception {
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        aliases.put("col_a", List.of("金额合计"));
        aliases.put("col_b", List.of("金额合计"));
        FieldAligner aligner = new FieldAligner(aliases);

        Map<Group, List<AlignmentResult>> grouped =
                aligner.alignAll(List.of("COL_A", "COL_B"), loadReal());

        assertEquals(0, grouped.get(Group.ALIAS).size(), "多列对同一清单字段不得自动合并");
        assertEquals(2, grouped.get(Group.AMBIGUOUS).size(), "两条都进待人工裁决");
    }
}
