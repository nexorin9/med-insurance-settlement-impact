package com.chaosforge.medins.report;

import com.chaosforge.medins.model.ImpactRecord;
import com.chaosforge.medins.model.ImpactScope;
import com.chaosforge.medins.model.ImpactType;
import com.chaosforge.medins.model.ListField;
import com.chaosforge.medins.model.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 两版影响面差异回放测试：五类差异、主键含分区、来源改名、CSV/中文导出。 */
class TwoVersionDiffTest {

    private static ImpactRecord record(String section, String field, String source,
                                       Verdict verdict, ImpactScope scope) {
        return ImpactRecord.builder()
                .viewName("V_BILL_FEE")
                .changedField("FEE_AMT")
                .section(section)
                .listFieldName(field)
                .sourceColumn(source)
                .impact(ImpactType.DIRECT)
                .confidence(0.9d)
                .scope(scope)
                .hops(1)
                .batch("<EXAMPLE_BATCH>")
                .verdict(verdict)
                .reasonZh("r")
                .build();
    }

    private static boolean contains(List<DiffResult.Entry> entries, String section, String field) {
        return entries.stream().anyMatch(e -> e.section().equals(section) && e.fieldName().equals(field));
    }

    @Test
    @DisplayName("新增/消失/判定变化/口径变化/来源变化五类分类正确")
    void fiveKindsClassified() {
        String fee = ListField.SECTION_FEE;
        String inpatient = ListField.SECTION_INPATIENT;
        String basic = ListField.SECTION_BASIC;

        ImpactRecord stable = record(fee, "医疗费用总额", "FEE_AMT",
                Verdict.MATERIALLY_AFFECTED, ImpactScope.STRICT);
        List<ImpactRecord> older = List.of(
                stable,
                record(fee, "自费金额", "PAY_AMT", Verdict.SUSPECTED, ImpactScope.STRICT),
                record(fee, "统筹金额", "FUND_AMT", Verdict.MATERIALLY_AFFECTED, ImpactScope.STRICT),
                record(inpatient, "住院天数", "ADMIT_DATE", Verdict.MATERIALLY_AFFECTED, ImpactScope.STRICT),
                record(basic, "仅旧版字段", "X", Verdict.MATERIALLY_AFFECTED, ImpactScope.STRICT));
        List<ImpactRecord> newer = List.of(
                stable,
                record(fee, "自费金额", "PAY_AMT", Verdict.MATERIALLY_AFFECTED, ImpactScope.STRICT),
                record(fee, "统筹金额", "FUND_AMT", Verdict.MATERIALLY_AFFECTED, ImpactScope.CONSERVATIVE),
                record(inpatient, "住院天数", "ADMIT_DATE_OLD", Verdict.MATERIALLY_AFFECTED, ImpactScope.STRICT),
                record(basic, "仅新版字段", "Y", Verdict.MATERIALLY_AFFECTED, ImpactScope.STRICT));

        DiffResult diff = TwoVersionDiff.compare(older, newer);

        assertEquals(1, diff.added().size(), "新增 1 条");
        assertTrue(contains(diff.added(), basic, "仅新版字段"));
        assertEquals(1, diff.removed().size(), "消失 1 条");
        assertTrue(contains(diff.removed(), basic, "仅旧版字段"));

        assertEquals(1, diff.verdictChanged().size(), "判定变化 1 条");
        assertTrue(contains(diff.verdictChanged(), fee, "自费金额"));
        assertEquals("疑似", diff.verdictChanged().get(0).before());
        assertEquals("实质受影响", diff.verdictChanged().get(0).after());

        assertEquals(1, diff.scopeChanged().size(), "口径变化 1 条");
        assertTrue(contains(diff.scopeChanged(), fee, "统筹金额"));
        assertEquals("严格口径", diff.scopeChanged().get(0).before());
        assertEquals("保守口径", diff.scopeChanged().get(0).after());

        assertEquals(1, diff.sourceChanged().size(), "来源变化 1 条");
        assertTrue(contains(diff.sourceChanged(), inpatient, "住院天数"));
        assertEquals("ADMIT_DATE", diff.sourceChanged().get(0).before());
        assertEquals("ADMIT_DATE_OLD", diff.sourceChanged().get(0).after());

        assertEquals(5, diff.total(), "合计 5 条差异");
        assertFalse(diff.toCsv().contains("医疗费用总额"), "完全不变的稳定字段不得出现在差异里");
    }

    @Test
    @DisplayName("同名不同分区的字段未被误判为差异")
    void sameNameDifferentSectionNotMismatched() {
        List<ImpactRecord> older = List.of(
                record(ListField.SECTION_BASIC, "费用总额", "A", Verdict.SUSPECTED, ImpactScope.STRICT),
                record(ListField.SECTION_FEE, "费用总额", "B", Verdict.MATERIALLY_AFFECTED, ImpactScope.STRICT));
        List<ImpactRecord> newer = List.of(
                record(ListField.SECTION_BASIC, "费用总额", "A", Verdict.SUSPECTED, ImpactScope.STRICT),
                record(ListField.SECTION_FEE, "费用总额", "B", Verdict.MATERIALLY_AFFECTED, ImpactScope.STRICT));

        DiffResult diff = TwoVersionDiff.compare(older, newer);

        assertEquals(0, diff.total(), "两版完全一致，同名不同分区不应产生任何差异");
        assertEquals(0, diff.verdictChanged().size(), "不得把不同分区的同名字段互相比较");
        assertEquals(0, diff.scopeChanged().size());
        assertEquals(0, diff.sourceChanged().size());
    }

    @Test
    @DisplayName("来源列改名只归入来源变化，不算新增/消失，且可导出 CSV 与中文说明")
    void sourceRenameAndExport() {
        List<ImpactRecord> older = List.of(
                record(ListField.SECTION_FEE, "医疗费用总额", "FEE_AMT",
                        Verdict.MATERIALLY_AFFECTED, ImpactScope.STRICT));
        List<ImpactRecord> newer = List.of(
                record(ListField.SECTION_FEE, "医疗费用总额", "FEE_AMT_V2",
                        Verdict.MATERIALLY_AFFECTED, ImpactScope.STRICT));

        DiffResult diff = TwoVersionDiff.compare(older, newer);

        assertEquals(0, diff.added().size(), "来源改名不算新增");
        assertEquals(0, diff.removed().size(), "来源改名不算消失");
        assertEquals(0, diff.verdictChanged().size());
        assertEquals(0, diff.scopeChanged().size());
        assertEquals(1, diff.sourceChanged().size(), "来源改名归入来源变化");
        assertEquals(DiffResult.Kind.SOURCE_CHANGED, diff.sourceChanged().get(0).kind());
        assertTrue(diff.sourceChanged().get(0).uniqueKey().contains(ListField.SECTION_FEE),
                "主键须含分区");

        String csv = diff.toCsv();
        assertTrue(csv.startsWith("差异类型,分区,字段,变化前,变化后"), "差异 CSV 列头正确");
        assertTrue(csv.contains("来源变化"), "差异 CSV 含类别");
        assertTrue(csv.contains("FEE_AMT_V2"), "差异 CSV 含变化后来源列");
        String zh = diff.toZh();
        assertTrue(zh.contains("来源变化"), "中文说明含类别");
        assertTrue(zh.contains("FEE_AMT"), "中文说明含变化前后摘要");
        assertTrue(zh.contains("草稿"), "中文说明标注草稿供内审核对");
    }

    @Test
    @DisplayName("空输入不抛异常，仍可导出")
    void emptyInput() {
        DiffResult diff = TwoVersionDiff.compare(List.of(), List.of());

        assertEquals(0, diff.total());
        assertTrue(diff.toCsv().contains("差异类型"));
        assertTrue(diff.toZh().contains("草稿"));
    }
}
