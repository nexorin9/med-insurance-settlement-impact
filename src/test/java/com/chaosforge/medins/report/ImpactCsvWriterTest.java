package com.chaosforge.medins.report;

import com.chaosforge.medins.model.ImpactRecord;
import com.chaosforge.medins.model.ImpactScope;
import com.chaosforge.medins.model.ImpactType;
import com.chaosforge.medins.model.ListField;
import com.chaosforge.medins.model.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 影响面 CSV 导出测试：列头、RFC4180 转义、BOM、批次占位与批次汇总。 */
class ImpactCsvWriterTest {

    private static ImpactRecord record(String field, String source, String reason,
                                       String batch, double confidence) {
        return ImpactRecord.builder()
                .viewName("V_BILL_FEE")
                .changedField("FEE_AMT")
                .section(ListField.SECTION_FEE)
                .listFieldName(field)
                .sourceColumn(source)
                .impact(ImpactType.DIRECT)
                .confidence(confidence)
                .scope(ImpactScope.STRICT)
                .hops(2)
                .batch(batch)
                .verdict(Verdict.MATERIALLY_AFFECTED)
                .reasonZh(reason)
                .build();
    }

    @Test
    @DisplayName("列头与行数正确，每行十列")
    void headerAndRowCount() {
        List<ImpactRecord> rows = List.of(
                record("医疗费用总额", "FEE_AMT", "直接写入", "<EXAMPLE_BATCH>", 0.92d),
                record("自费金额", "PAY_AMT", "间接影响", "<EXAMPLE_BATCH>", 0.41d));
        String csv = ImpactCsvWriter.toCsv(rows);

        assertTrue(csv.startsWith(ImpactCsvWriter.HEADER), "列头不正确");
        List<List<String>> parsed = parseCsv(csv);
        assertEquals(3, parsed.size(), "应为表头 + 2 行");
        for (List<String> row : parsed) {
            assertEquals(10, row.size(), "每行必须恰好 10 列");
        }
        assertEquals("分区", parsed.get(0).get(0));
        assertEquals("理由", parsed.get(0).get(9));
        assertEquals("医疗费用总额", parsed.get(1).get(1));
    }

    @Test
    @DisplayName("含逗号、双引号与换行的字段按 RFC4180 转义，换行不影响列切分")
    void rfc4180EscapingAndLineBreaks() {
        String tricky = "经 V_IP_REG 中转, 含半角逗号;\n第二行有\"双引号\",需人工裁决";
        List<ImpactRecord> rows = List.of(
                record("医疗费用总额", "FEE_AMT", tricky, "<EXAMPLE_BATCH>", 0.92d));
        String csv = ImpactCsvWriter.toCsv(rows);

        assertTrue(csv.contains("\""), "含特殊字符的字段应被引号包裹");
        assertTrue(csv.contains("\"\""), "内部双引号应翻倍转义");
        List<List<String>> parsed = parseCsv(csv);
        assertEquals(2, parsed.size(), "理由里的换行不得切出多余行");
        assertEquals(10, parsed.get(1).size(), "理由换行不影响列切分");
        assertEquals(tricky, parsed.get(1).get(9), "理由文本须完整还原");
        assertEquals("FEE_AMT", parsed.get(1).get(2), "来源列仍在正确列位");
        assertEquals("<EXAMPLE_BATCH>", parsed.get(1).get(7), "批次列仍在正确列位");
    }

    @Test
    @DisplayName("文件为 UTF-8 BOM，批次列为占位符且不泄露真实批次号")
    void utf8BomAndBatchPlaceholder(@TempDir Path dir) throws Exception {
        List<ImpactRecord> rows = List.of(
                record("医疗费用总额", "FEE_AMT", "直接写入", "<UPSTREAM_ONLY>", 0.92d));
        Path file = dir.resolve("impact.csv");
        ImpactCsvWriter.write(file, rows);

        byte[] bytes = Files.readAllBytes(file);
        assertArrayEquals(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                Arrays.copyOfRange(bytes, 0, 3), "文件头须为 UTF-8 BOM");
        String content = new String(bytes, StandardCharsets.UTF_8);
        List<List<String>> parsed = parseCsv(content);
        assertEquals("<EXAMPLE_BATCH>", parsed.get(1).get(7), "批次列须为占位符");
        assertFalse(content.contains("UPSTREAM_ONLY"), "上游批次字面量应被归一脱敏");
    }

    @Test
    @DisplayName("按批次汇总受影响字段数与必填字段数，可一键导出")
    void summarizeByBatch() {
        List<ImpactRecord> rows = List.of(
                record("医疗费用总额", "FEE_AMT", "r1", "<EXAMPLE_BATCH>", 0.92d),
                record("医疗费用总额", "FEE_AMT", "r1b", "<EXAMPLE_BATCH>", 0.90d),
                record("自费金额", "PAY_AMT", "r2", "<EXAMPLE_BATCH>", 0.41d),
                record("住院天数", "ADMIT_DATE", "r3", "<EXAMPLE_BATCH>", 0.88d));

        Map<String, BatchSummary> summary = ImpactCsvWriter.summarizeByBatch(rows);
        assertTrue(summary.containsKey("<EXAMPLE_BATCH>"), "应按占位符批次汇总");
        BatchSummary plain = summary.get("<EXAMPLE_BATCH>");
        assertEquals(3, plain.affectedFieldCount(), "去重后应为 3 个受影响字段");
        assertEquals(4, plain.recordCount(), "记录条数为 4");
        assertEquals("<EXAMPLE_BATCH>", plain.toCsvRow().split(",")[0], "汇总行可导出");
        assertTrue(plain.toZh().contains("受影响字段 3"), "中文汇总含受影响字段数");
        assertTrue(BatchSummary.HEADER.contains("必填字段数"), "汇总列头含必填字段数");

        Predicate<ImpactRecord> required = r ->
                Set.of("医疗费用总额", "住院天数").contains(r.listFieldName());
        BatchSummary withRequired = ImpactCsvWriter.summarizeByBatch(rows, required).get("<EXAMPLE_BATCH>");
        assertEquals(2, withRequired.requiredFieldCount(), "必填字段应为 2 项");
        assertTrue(withRequired.toZh().contains("必填字段 2"), "中文汇总含必填字段数");
    }

    /**
     * 测试内的 RFC4180 解析器：去 BOM、支持引号内换行与双引号翻倍，用于 round-trip 断言。
     */
    private static List<List<String>> parseCsv(String text) {
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n') {
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else if (c == '\r') {
                // 等待随后的 \n，忽略 CR
            } else {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }
}
