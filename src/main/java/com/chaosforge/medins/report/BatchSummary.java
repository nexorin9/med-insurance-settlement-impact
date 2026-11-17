package com.chaosforge.medins.report;

import com.chaosforge.medins.model.ImpactRecord;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 单个批次的影响面汇总（纯数据）。
 *
 * <p>受影响字段数按「分区 + 字段名」去重；必填字段数同样去重，由调用方经
 * {@link ImpactCsvWriter} 的带判定函数重载注入必填判定
 * （清单字典的必填位不在 report 模块的依赖范围内）。
 */
public final class BatchSummary {

    /** 汇总导出的列头。 */
    public static final String HEADER = "批次,受影响字段数,必填字段数,记录数";

    private final String batch;
    private final int affectedFieldCount;
    private final int requiredFieldCount;
    private final int recordCount;

    private BatchSummary(String batch, int affectedFieldCount, int requiredFieldCount, int recordCount) {
        this.batch = batch;
        this.affectedFieldCount = affectedFieldCount;
        this.requiredFieldCount = requiredFieldCount;
        this.recordCount = recordCount;
    }

    public String batch() {
        return batch;
    }

    /** 去重后的受影响字段数。 */
    public int affectedFieldCount() {
        return affectedFieldCount;
    }

    /** 去重后的必填字段数（需调用方注入必填判定）。 */
    public int requiredFieldCount() {
        return requiredFieldCount;
    }

    /** 该批次下的记录条数（含不同口径/不同判定）。 */
    public int recordCount() {
        return recordCount;
    }

    /** 一键汇总导出时的 CSV 行。 */
    public String toCsvRow() {
        return ReportText.escapeCsv(batch) + ","
                + affectedFieldCount + ","
                + requiredFieldCount + ","
                + recordCount;
    }

    /** 中文汇总说明，供清算前按批次核对。 */
    public String toZh() {
        return "批次 " + batch + "：受影响字段 " + affectedFieldCount
                + " 项，其中必填字段 " + requiredFieldCount
                + " 项（记录 " + recordCount + " 条）";
    }

    /** 包内累加器，按唯一键去重统计。 */
    static final class Acc {

        private final String batch;
        private final Set<String> affected = new LinkedHashSet<>();
        private final Set<String> required = new LinkedHashSet<>();
        private int recordCount;

        Acc(String batch) {
            this.batch = batch;
        }

        void add(ImpactRecord record, boolean isRequired) {
            String key = ReportText.fieldKey(record);
            affected.add(key);
            if (isRequired) {
                required.add(key);
            }
            recordCount++;
        }

        BatchSummary build() {
            return new BatchSummary(batch, affected.size(), required.size(), recordCount);
        }
    }
}
