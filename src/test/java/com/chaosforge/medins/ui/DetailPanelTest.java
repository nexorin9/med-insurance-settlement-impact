package com.chaosforge.medins.ui;

import com.chaosforge.medins.model.ImpactRecord;
import com.chaosforge.medins.model.ImpactScope;
import com.chaosforge.medins.model.ImpactType;
import com.chaosforge.medins.model.ListField;
import com.chaosforge.medins.model.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 详情抽屉测试：逐跳证据链、口径色标、置信度、四档判定、待核对条目。 */
class DetailPanelTest {

    private static final String CHAIN =
            "V_BILL_FEE.FEE_AMT --DERIVED--> V_IP_REG.ADMIT_DATE --DIRECT--> 住院天数";

    @Test
    @DisplayName("证据链逐跳渲染：字段路径、每跳 edge_kind 与累计跳数")
    void evidenceHopByHop() {
        List<String> lines = DetailPanel.renderEvidence(CHAIN);
        assertEquals(3, lines.size(), "字段路径 + 2 跳");
        assertTrue(lines.get(0).startsWith("字段路径："));
        assertTrue(lines.get(0).contains("V_BILL_FEE.FEE_AMT"));
        assertTrue(lines.get(0).contains("V_IP_REG.ADMIT_DATE"));
        assertTrue(lines.get(0).contains("住院天数"));
        assertTrue(lines.get(0).contains("→"), "字段路径用箭头连接");

        assertTrue(lines.get(1).contains("第 1 跳"));
        assertTrue(lines.get(1).contains("DERIVED"), "第一跳边类型");
        assertTrue(lines.get(1).contains("累计跳数：1"));
        assertTrue(lines.get(2).contains("第 2 跳"));
        assertTrue(lines.get(2).contains("DIRECT"), "第二跳边类型");
        assertTrue(lines.get(2).contains("累计跳数：2"));
    }

    @Test
    @DisplayName("空证据链与无箭头短句不抛异常，给出兜底文案")
    void evidenceFallback() {
        List<String> blank = DetailPanel.renderEvidence("  ");
        assertEquals(1, blank.size());
        assertTrue(blank.get(0).contains("（未提供）"));
        List<String> plain = DetailPanel.renderEvidence("仅一句中文短句，无箭头");
        assertEquals(1, plain.size());
        assertTrue(plain.get(0).contains("证据链："));
    }

    @Test
    @DisplayName("严格/保守口径不同色标，置信度低于阈值标注需人工确认")
    void scopeMarkAndConfidence() {
        String strict = DetailPanel.scopeMark(ImpactScope.STRICT);
        String conservative = DetailPanel.scopeMark(ImpactScope.CONSERVATIVE);
        assertNotEquals(strict, conservative);
        assertNotEquals(Theme.STRICT_MARK, Theme.CONSERVATIVE_MARK, "两种口径色标不同");
        assertTrue(strict.contains("严格口径") && strict.contains(Theme.STRICT_MARK));
        assertTrue(conservative.contains("保守口径") && conservative.contains(Theme.CONSERVATIVE_MARK));

        String low = DetailPanel.confidenceNote(0.41d);
        String high = DetailPanel.confidenceNote(0.92d);
        assertTrue(low.contains("疑似波及，需人工确认"));
        assertFalse(high.contains("疑似波及，需人工确认"));
        assertTrue(high.contains("0.92"));
        assertTrue(low.contains("0.41"));
    }

    @Test
    @DisplayName("判定四档各有呈现，不可判定给出「需人工裁决」入口")
    void fourVerdicts() {
        Set<String> presentations = new HashSet<>();
        for (Verdict verdict : List.of(Verdict.MATERIALLY_AFFECTED, Verdict.SUSPECTED,
                Verdict.SAME_NAME_DIFFERENT_MEANING, Verdict.UNDETERMINABLE)) {
            presentations.add(DetailPanel.verdictPresentation(verdict));
        }
        assertEquals(4, presentations.size(), "四档呈现互不相同");
        assertTrue(DetailPanel.verdictPresentation(Verdict.MATERIALLY_AFFECTED).contains("实质受影响"));
        assertTrue(DetailPanel.verdictPresentation(Verdict.SUSPECTED).contains("疑似"));
        assertTrue(DetailPanel.verdictPresentation(Verdict.SAME_NAME_DIFFERENT_MEANING).contains("同名不同义"));
        assertTrue(DetailPanel.verdictPresentation(Verdict.UNDETERMINABLE).contains("需人工裁决"));
    }

    @Test
    @DisplayName("详情抽屉单列待人工核对条目一节，含原因与位置")
    void pendingReviewSectionVisible() {
        ImpactRecord record = ImpactRecord.builder()
                .viewName("V_BILL_FEE")
                .changedField("FEE_AMT")
                .section(ListField.SECTION_FEE)
                .listFieldName("医疗费用总额")
                .sourceColumn("FEE_AMT")
                .impact(ImpactType.DIRECT)
                .confidence(0.41d)
                .scope(ImpactScope.CONSERVATIVE)
                .hops(3)
                .batch("<EXAMPLE_BATCH>")
                .verdict(Verdict.UNDETERMINABLE)
                .reasonZh("r")
                .build();
        List<String> reviewItems = List.of(
                "SELECT_STAR ｜ 位置 12:3 ｜ 原因：星号取列无法安全归并",
                "AMBIGUOUS_COLUMN ｜ 位置 8:15 ｜ 原因：同名列未限定名，存在歧义",
                "DIALECT_UNSUPPORTED ｜ 位置 1:1 ｜ 原因：MySQL 反引号方言不支持");

        List<String> lines = DetailPanel.renderDetail(record, CHAIN, reviewItems);
        String joined = String.join("\n", lines);

        assertTrue(joined.contains("字段路径"), "证据链呈现");
        assertTrue(joined.contains("保守口径"), "口径呈现");
        assertTrue(joined.contains("疑似波及，需人工确认"), "低置信度呈现");
        assertTrue(joined.contains("需人工裁决"), "不可判定入口");
        assertTrue(joined.contains("待人工核对条目"), "单列一节");
        assertTrue(joined.contains("星号取列"), "星号取列条目可见");
        assertTrue(joined.contains("歧义"), "歧义条目可见");
        assertTrue(joined.contains("方言不支持"), "方言不支持条目可见");
        assertTrue(joined.contains("原因") && joined.contains("位置"), "条目含原因与位置");
    }
}
