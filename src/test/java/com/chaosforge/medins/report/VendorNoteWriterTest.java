package com.chaosforge.medins.report;

import com.chaosforge.medins.model.ChangeKind;
import com.chaosforge.medins.model.ImpactRecord;
import com.chaosforge.medins.model.ImpactScope;
import com.chaosforge.medins.model.ImpactStatus;
import com.chaosforge.medins.model.ImpactType;
import com.chaosforge.medins.model.ListField;
import com.chaosforge.medins.model.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 厂商说明生成测试：章节、口径、草稿标注、批次占位与证据链转述。 */
class VendorNoteWriterTest {

    /** 故意把上游批次写成非占位符的内部标记，用于验证输出层一律归一、不泄露上游字面量。 */
    private static ImpactRecord record(String section, String field, String source,
                                       ImpactScope scope, Verdict verdict,
                                       double confidence, int hops, String reason) {
        return ImpactRecord.builder()
                .viewName("V_BILL_FEE")
                .changedField("FEE_AMT")
                .changeKind(ChangeKind.LOGIC_CHANGED)
                .section(section)
                .listFieldName(field)
                .sourceColumn(source)
                .impact(ImpactType.DIRECT)
                .confidence(confidence)
                .scope(scope)
                .hops(hops)
                .batch("<UPSTREAM_ONLY>")
                .verdict(verdict)
                .reasonZh(reason)
                .status(ImpactStatus.PENDING_CONFIRM)
                .build();
    }

    private static NoteContext context() {
        Map<String, String> evidence = new LinkedHashMap<>();
        evidence.put("医疗费用总额", "V_BILL_FEE.FEE_AMT --DIRECT--> 医疗费用总额（1 跳）");
        evidence.put("住院天数", "V_BILL_FEE.FEE_AMT --DERIVED--> V_IP_REG.ADMIT_DATE --DIRECT--> 住院天数（2 跳）");
        return NoteContext.builder()
                .viewName("V_BILL_FEE")
                .changedField("FEE_AMT")
                .changeKind(ChangeKind.LOGIC_CHANGED)
                .changeNoteZh("原按单价×数量汇总，改为按含税金额汇总")
                .sqlDigest("a1b2c3d4e5f6a7b8c9d0")
                .scope(ImpactScope.STRICT)
                .degraded(false)
                .evidence(evidence)
                .build();
    }

    @Test
    @DisplayName("章节齐全、含分区字段与来源列、含口径与跳数与置信度")
    void sectionsColumnsScopeAndHops() {
        List<ImpactRecord> rows = List.of(
                record(ListField.SECTION_FEE, "医疗费用总额", "FEE_AMT",
                        ImpactScope.STRICT, Verdict.MATERIALLY_AFFECTED, 0.92d, 1, "直接写入该栏"),
                record(ListField.SECTION_INPATIENT, "住院天数", "ADMIT_DATE",
                        ImpactScope.CONSERVATIVE, Verdict.SUSPECTED, 0.41d, 3, "经筛选条件列间接影响"));
        String note = VendorNoteWriter.write(rows, context());

        assertTrue(note.contains("# 取数视图"), "缺少标题");
        assertTrue(note.contains("## 改动点"), "缺少改动点");
        assertTrue(note.contains("## 受影响结算清单字段"), "缺少受影响字段章节");
        assertTrue(note.contains("## 批次"), "缺少批次章节");
        assertTrue(note.contains("## 逐条理由"), "缺少逐条理由章节");
        assertTrue(note.contains("- 视图：V_BILL_FEE"), "缺少视图");
        assertTrue(note.contains("- 改动列：FEE_AMT"), "缺少改动列");
        assertTrue(note.contains("逻辑变更"), "缺少改动类型");
        assertTrue(note.contains("原按单价×数量汇总，改为按含税金额汇总"), "缺少改动说明");
        assertTrue(note.contains(ListField.SECTION_FEE), "缺少分区");
        assertTrue(note.contains("医疗费用总额"), "缺少字段");
        assertTrue(note.contains("FEE_AMT"), "缺少来源列");
        assertTrue(note.contains("严格口径") && note.contains("保守口径"), "未同时标明严格/保守口径");
        assertTrue(note.contains("跳数"), "缺少跳数");
        assertTrue(note.contains("置信度"), "缺少置信度");
        assertTrue(note.contains("0.92") && note.contains("0.41"), "缺少逐条置信度值");
    }

    @Test
    @DisplayName("标为草稿、批次为占位符、不泄露真实批次号与敏感信息")
    void draftBatchPlaceholderAndNoSecrets() {
        List<ImpactRecord> rows = List.of(
                record(ListField.SECTION_FEE, "医疗费用总额", "FEE_AMT",
                        ImpactScope.STRICT, Verdict.MATERIALLY_AFFECTED, 0.92d, 1, "直接写入该栏"));
        String note = VendorNoteWriter.write(rows, context());
        String lower = note.toLowerCase();

        assertTrue(note.contains("草稿（未人工确认）"), "未标为草稿（未人工确认）");
        assertTrue(note.contains("<EXAMPLE_BATCH>"), "批次不是占位符");
        assertFalse(note.contains("UPSTREAM_ONLY"), "不得泄露上游批次字面量");
        assertFalse(Pattern.compile("\\b\\d{6,}\\b").matcher(note).find(), "存在形似真实批次号的长数字字面量");
        for (String secret : List.of("身份证号：", "患者姓名：", "病历号：", "bearer ", "password=", "api_key=")) {
            assertFalse(lower.contains(secret), "出现敏感信息：" + secret);
        }
    }

    @Test
    @DisplayName("逐条理由原样转述证据链短句与归因理由，不重新编造措辞")
    void reasonsReuseEvidenceAndAttribution() {
        List<ImpactRecord> rows = List.of(
                record(ListField.SECTION_FEE, "医疗费用总额", "FEE_AMT",
                        ImpactScope.STRICT, Verdict.MATERIALLY_AFFECTED, 0.92d, 1, "直接写入该栏"));
        String note = VendorNoteWriter.write(rows, context());

        assertTrue(note.contains("V_BILL_FEE.FEE_AMT --DIRECT--> 医疗费用总额（1 跳）"),
                "证据链短句未原样转述");
        assertTrue(note.contains("- 证据链："), "缺少证据链行");
        assertTrue(note.contains("- 归因理由：直接写入该栏"), "归因理由未原样转述");
        assertTrue(note.contains("实质受影响"), "缺少判定呈现");
    }

    @Test
    @DisplayName("降级状态与空记录不抛异常且文案齐全")
    void degradedAndEmptyRecords() {
        String note = VendorNoteWriter.write(List.of(),
                NoteContext.builder()
                        .viewName("V_X")
                        .changedField("C")
                        .degraded(true)
                        .build());

        assertTrue(note.contains("归因未生成"), "缺少降级提示");
        assertTrue(note.contains("本次没有命中受影响字段"), "缺少空记录文案");
        assertTrue(note.contains("草稿（未人工确认）"), "仍须标为草稿");
        assertTrue(note.contains("<EXAMPLE_BATCH>"), "空记录也带批次占位");
    }
}
