package com.chaosforge.medins.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 领域模型测试 —— 验证字段键规范形式与影响面记录的回写闸门。
 */
class ImpactRecordTest {

    @Test
    @DisplayName("字段键统一大写并以 VIEW.COLUMN 呈现")
    void fieldKeyNormalizesCase() {
        FieldKey key = FieldKey.of("v_bill_fee", "fee_amt");

        assertEquals("V_BILL_FEE", key.view());
        assertEquals("FEE_AMT", key.column());
        assertEquals("V_BILL_FEE.FEE_AMT", key.key());
        assertEquals(key, FieldKey.of("V_BILL_FEE", "FEE_AMT"));
        assertEquals(key.hashCode(), FieldKey.of("V_BILL_FEE", "FEE_AMT").hashCode());
    }

    @Test
    @DisplayName("字段键的视图名或列名为空时报错")
    void fieldKeyRejectsEmptyParts() {
        assertThrows(IllegalArgumentException.class, () -> FieldKey.of("", "FEE_AMT"));
        assertThrows(IllegalArgumentException.class, () -> FieldKey.of("V_BILL_FEE", "  "));
    }

    @Test
    @DisplayName("未人工确认的记录不允许构造回写请求")
    void writeBackRequiresHumanConfirmation() {
        ImpactRecord record = ImpactRecord.builder()
                .idempotencyKey("abc123")
                .viewName("V_BILL_FEE")
                .changedField("FEE_AMT")
                .changeKind(ChangeKind.LOGIC_CHANGED)
                .section(ListField.SECTION_FEE)
                .listFieldName("医疗费用总额")
                .sourceColumn("FEE_AMT")
                .impact(ImpactType.DIRECT)
                .confidence(0.92d)
                .scope(ImpactScope.STRICT)
                .hops(1)
                .batch("<EXAMPLE_BATCH>")
                .status(ImpactStatus.PENDING_CONFIRM)
                .build();

        assertFalse(record.isWriteBackEligible());
    }

    @Test
    @DisplayName("人工确认后带出确认人与确认时间，回写闸门打开")
    void humanConfirmationOpensWriteBackGate() {
        ImpactRecord record = ImpactRecord.builder()
                .viewName("V_BILL_FEE")
                .changedField("FEE_AMT")
                .section(ListField.SECTION_FEE)
                .listFieldName("医疗费用总额")
                .impact(ImpactType.DIRECT)
                .confidence(0.9d)
                .status(ImpactStatus.PENDING_CONFIRM)
                .build();

        ImpactRecord confirmed = record.withHumanConfirmation("医保办-王专员", "2026-11-09T08:50:00Z");

        assertEquals(ImpactStatus.CONFIRMED, confirmed.status());
        assertEquals("医保办-王专员", confirmed.confirmedBy());
        assertTrue(confirmed.isWriteBackEligible());
        assertFalse(record.isWriteBackEligible());
    }

    @Test
    @DisplayName("置信度越界与负跳数被拒绝")
    void invalidConfidenceAndHopsRejected() {
        ImpactRecord.Builder builder = ImpactRecord.builder()
                .viewName("V_BILL_FEE")
                .changedField("FEE_AMT");

        assertThrows(IllegalArgumentException.class, () -> builder.confidence(1.5d));
        assertThrows(IllegalArgumentException.class, () -> builder.hops(-1));
    }

    @Test
    @DisplayName("归因判定四档：同名不同义与不可判定不计入实质受影响")
    void verdictClassification() {
        assertTrue(Verdict.MATERIALLY_AFFECTED.countsAsMaterialImpact());
        assertFalse(Verdict.SAME_NAME_DIFFERENT_MEANING.countsAsMaterialImpact());
        assertFalse(Verdict.UNDETERMINABLE.countsAsMaterialImpact());
        assertTrue(Verdict.SAME_NAME_DIFFERENT_MEANING.needsHumanArbitration());
        assertTrue(Verdict.SUSPECTED.needsHumanArbitration());
        assertTrue(Verdict.UNDETERMINABLE.needsHumanArbitration());
        assertFalse(Verdict.MATERIALLY_AFFECTED.needsHumanArbitration());
    }
}
