package com.chaosforge.medins.report;

import com.chaosforge.medins.model.ImpactRecord;

import java.util.Locale;

/**
 * report 包内共享的文本工具：批次归一、CSV 转义、置信度格式、字段主键。
 *
 * <p>集中在单处，保证厂商说明、影响面 CSV、两版差异回放对同一概念使用同一套规则。
 * 本类只依赖 model 层，不依赖任何 UI 或其它模块。
 */
final class ReportText {

    /** 样例数据批次占位符；所有产物中一律使用该字面量。 */
    static final String EXAMPLE_BATCH = "<EXAMPLE_BATCH>";

    /** 主键分隔符：选用不会出现在中文分区名/字段名里的控制字符。 */
    private static final String KEY_SEP = "\u0001";

    private ReportText() {
    }

    /**
     * 批次归一：输出层不信任上游批次字面量。
     *
     * <p>样例阶段一律归为 {@code <EXAMPLE_BATCH>}；对任何疑似真实批次号的字面量做脱敏归一，
     * 从根上杜绝真实批次号进入说明、CSV 与批次汇总产物。
     */
    static String safeBatch(String raw) {
        if (raw == null || EXAMPLE_BATCH.equals(raw)) {
            return EXAMPLE_BATCH;
        }
        // 非占位符字面量疑似真实批次号，一律脱敏为占位符
        return EXAMPLE_BATCH;
    }

    static String safeBatch(ImpactRecord record) {
        return safeBatch(record.batch());
    }

    /**
     * RFC4180 转义：字段内含逗号、双引号或 CR/LF 时用双引号包裹，内部双引号翻倍。
     * 这样理由文本里的换行不会破坏列切分。
     */
    static String escapeCsv(String raw) {
        String value = raw == null ? "" : raw;
        boolean needsQuote = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        if (!needsQuote) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    /** 置信度统一保留两位小数，避免不同产物里精度不一致。 */
    static String fmtConfidence(double confidence) {
        return String.format(Locale.ROOT, "%.2f", confidence);
    }

    /** 以「分区 + 字段名」构造主键：同名不同分区不会碰撞。 */
    static String fieldKey(String section, String fieldName) {
        return (section == null ? "" : section) + KEY_SEP + (fieldName == null ? "" : fieldName);
    }

    static String fieldKey(ImpactRecord record) {
        return fieldKey(record.section(), record.listFieldName());
    }

    /** 摘要只取前若干位用于展示，避免长串进入说明文本。 */
    static String shortDigest(String digest) {
        if (digest == null || digest.isBlank()) {
            return "（未提供）";
        }
        String trimmed = digest.trim();
        return trimmed.length() <= 8 ? trimmed : trimmed.substring(0, 8) + "…";
    }
}
