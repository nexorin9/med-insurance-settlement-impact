package com.chaosforge.medins.service;

import com.chaosforge.medins.model.ImpactRecord;
import com.chaosforge.medins.model.ImpactStatus;
import com.chaosforge.medins.sqlparse.ManualReviewItem;
import com.chaosforge.medins.sqlparse.ParseReport;

import java.util.ArrayList;
import java.util.List;

/**
 * 影响面分析结果：记录集、整体状态、降级标记、待人工核对清单、解析报告、九步顺序与中文摘要。
 *
 * <p>{@link #steps()} 固定返回九步顺序：读取 / 解析 / 构图 / 可达 / 对齐 / 归因 / 待确认 / 确认 / 回写。
 * 额外提供各降级分支的布尔标记，便于服务层与 UI 精确断言。
 */
public final class AnalysisResult {

    /** 主流程九步（顺序固定，契约 §3.4）。 */
    public static final List<String> STEPS = List.of(
            "读取视图定义",
            "SQL 解析",
            "图构建",
            "下游可达",
            "清单对齐",
            "归因",
            "待确认",
            "人工确认",
            "幂等回写");

    private final List<ImpactRecord> records;
    private final ImpactStatus status;
    private final boolean degraded;
    private final boolean graphStale;
    private final boolean writeBackEnabled;
    private final List<ManualReviewItem> reviewItems;
    private final ParseReport parseReport;
    private final List<String> steps;
    private final String summary;
    private final boolean dictionaryAvailable;
    private final boolean parseFailed;
    private final boolean modelUnavailable;
    private final boolean dbUnavailable;

    private AnalysisResult(Builder b) {
        this.records = List.copyOf(b.records);
        this.status = b.status == null ? ImpactStatus.PARSED : b.status;
        this.degraded = b.degraded;
        this.graphStale = b.graphStale;
        this.writeBackEnabled = b.writeBackEnabled;
        this.reviewItems = List.copyOf(b.reviewItems);
        this.parseReport = b.parseReport == null ? new ParseReport() : b.parseReport;
        this.steps = b.steps == null ? STEPS : List.copyOf(b.steps);
        this.summary = b.summary == null ? "" : b.summary;
        this.dictionaryAvailable = b.dictionaryAvailable;
        this.parseFailed = b.parseFailed;
        this.modelUnavailable = b.modelUnavailable;
        this.dbUnavailable = b.dbUnavailable;
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<ImpactRecord> records() { return records; }
    public ImpactStatus status() { return status; }
    public boolean degraded() { return degraded; }
    public boolean graphStale() { return graphStale; }
    public boolean writeBackEnabled() { return writeBackEnabled; }
    public List<ManualReviewItem> reviewItems() { return reviewItems; }
    public ParseReport parseReport() { return parseReport; }
    public List<String> steps() { return steps; }
    public String summary() { return summary; }

    public boolean dictionaryAvailable() { return dictionaryAvailable; }
    public boolean parseFailed() { return parseFailed; }
    public boolean modelUnavailable() { return modelUnavailable; }
    public boolean dbUnavailable() { return dbUnavailable; }

    public static final class Builder {
        private List<ImpactRecord> records = new ArrayList<>();
        private ImpactStatus status = ImpactStatus.PARSED;
        private boolean degraded;
        private boolean graphStale;
        private boolean writeBackEnabled;
        private List<ManualReviewItem> reviewItems = new ArrayList<>();
        private ParseReport parseReport = new ParseReport();
        private List<String> steps = STEPS;
        private String summary = "";
        private boolean dictionaryAvailable = true;
        private boolean parseFailed;
        private boolean modelUnavailable;
        private boolean dbUnavailable;

        private Builder() { }

        public Builder records(List<ImpactRecord> v) { this.records = v == null ? new ArrayList<>() : new ArrayList<>(v); return this; }
        public Builder status(ImpactStatus v) { this.status = v; return this; }
        public Builder degraded(boolean v) { this.degraded = v; return this; }
        public Builder graphStale(boolean v) { this.graphStale = v; return this; }
        public Builder writeBackEnabled(boolean v) { this.writeBackEnabled = v; return this; }
        public Builder reviewItems(List<ManualReviewItem> v) { this.reviewItems = v == null ? new ArrayList<>() : new ArrayList<>(v); return this; }
        public Builder parseReport(ParseReport v) { this.parseReport = v; return this; }
        public Builder steps(List<String> v) { this.steps = v; return this; }
        public Builder summary(String v) { this.summary = v; return this; }
        public Builder dictionaryAvailable(boolean v) { this.dictionaryAvailable = v; return this; }
        public Builder parseFailed(boolean v) { this.parseFailed = v; return this; }
        public Builder modelUnavailable(boolean v) { this.modelUnavailable = v; return this; }
        public Builder dbUnavailable(boolean v) { this.dbUnavailable = v; return this; }

        public AnalysisResult build() {
            return new AnalysisResult(this);
        }
    }
}
