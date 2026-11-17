package com.chaosforge.medins.report;

import com.chaosforge.medins.model.ImpactRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 影响面 CSV 导出与批次维度汇总。
 *
 * <p>列与界面表格一一对应，口径与批次必须带上，便于贴进 Excel、按批次筛选、与上次结果对比。
 * 文件输出为 UTF-8 带 BOM，字段按 RFC4180 转义。
 */
public final class ImpactCsvWriter {

    /** CSV 列头（顺序与契约 §3.5 一致）。 */
    public static final String HEADER = "分区,字段,来源列,影响类型,置信度,口径,跳数,批次,判定,理由";

    /** UTF-8 BOM，院内 Excel 直接双击打开不乱码。 */
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private ImpactCsvWriter() {
    }

    /** 生成不含 BOM 的 CSV 文本；行分隔符采用 CRLF，单元格按 RFC4180 转义。 */
    public static String toCsv(List<ImpactRecord> records) {
        StringBuilder sb = new StringBuilder();
        sb.append(HEADER).append("\r\n");
        if (records != null) {
            for (ImpactRecord record : records) {
                sb.append(csvRow(record)).append("\r\n");
            }
        }
        return sb.toString();
    }

    private static String csvRow(ImpactRecord r) {
        String[] cells = {
                r.section(),
                r.listFieldName(),
                r.sourceColumn(),
                r.impact() == null ? "" : r.impact().wireName(),
                ReportText.fmtConfidence(r.confidence()),
                r.scope() == null ? "" : r.scope().wireName(),
                String.valueOf(r.hops()),
                ReportText.safeBatch(r),
                r.verdict() == null ? "" : r.verdict().labelZh(),
                r.reasonZh()
        };
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                row.append(',');
            }
            row.append(ReportText.escapeCsv(cells[i]));
        }
        return row.toString();
    }

    /** 写出 UTF-8 带 BOM 的文件。 */
    public static void write(Path path, List<ImpactRecord> records) throws IOException {
        byte[] body = toCsv(records).getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[UTF8_BOM.length + body.length];
        System.arraycopy(UTF8_BOM, 0, all, 0, UTF8_BOM.length);
        System.arraycopy(body, 0, all, UTF8_BOM.length, body.length);
        Files.write(path, all);
    }

    /**
     * 按批次汇总（契约固定签名）。
     *
     * <p>受影响字段数可直接由记录推出；records 不携带清单字典的必填位，故必填字段数默认不推断，
     * 精确统计请用带 {@link Predicate} 的重载注入必填判定。
     */
    public static Map<String, BatchSummary> summarizeByBatch(List<ImpactRecord> records) {
        return summarizeByBatch(records, record -> false);
    }

    /** 按批次汇总：{@code isRequired} 由调用方按清单字段字典判定，支持一键汇总导出。 */
    public static Map<String, BatchSummary> summarizeByBatch(
            List<ImpactRecord> records, Predicate<ImpactRecord> isRequired) {
        Map<String, BatchSummary.Acc> accumulator = new LinkedHashMap<>();
        if (records != null) {
            for (ImpactRecord record : records) {
                String batch = ReportText.safeBatch(record);
                BatchSummary.Acc acc = accumulator.computeIfAbsent(batch, BatchSummary.Acc::new);
                acc.add(record, isRequired != null && isRequired.test(record));
            }
        }
        Map<String, BatchSummary> result = new LinkedHashMap<>();
        accumulator.forEach((key, acc) -> result.put(key, acc.build()));
        return result;
    }
}
