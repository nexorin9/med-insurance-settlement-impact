package com.chaosforge.medins.model;

import java.util.Objects;

/**
 * 影响面记录 —— 「改动点 → 受影响的某一栏结算清单字段」这一行。
 *
 * <p>一行 = 一个改动点 × 一栏清单字段 × 一个口径。它同时携带改动点上下文（视图、摘要、改动列、改动类型）、
 * 影响判定（影响类型、置信度、口径、跳数、归因判定、理由）与业务状态，
 * 因此可以直接落库、导出 CSV、拼装台账回写请求体，而不需要再回查别处。
 *
 * <p>时间字段统一为 ISO-8601 字符串（形如 {@code 2026-11-09T08:50:00Z}），避免引入额外的序列化模块。
 */
public final class ImpactRecord {

    private final String idempotencyKey;
    private final String viewName;
    private final String viewDialect;
    private final String sqlDigest;
    private final String changedField;
    private final ChangeKind changeKind;
    private final String section;
    private final String listFieldName;
    private final String sourceColumn;
    private final ImpactType impact;
    private final double confidence;
    private final ImpactScope scope;
    private final int hops;
    private final String batch;
    private final Verdict verdict;
    private final String reasonZh;
    private final ImpactStatus status;
    private final String confirmedBy;
    private final String confirmedAt;
    private final String occurredAt;
    private final String writeBackAt;

    private ImpactRecord(Builder builder) {
        this.idempotencyKey = builder.idempotencyKey;
        this.viewName = builder.viewName;
        this.viewDialect = builder.viewDialect;
        this.sqlDigest = builder.sqlDigest;
        this.changedField = builder.changedField;
        this.changeKind = builder.changeKind;
        this.section = builder.section;
        this.listFieldName = builder.listFieldName;
        this.sourceColumn = builder.sourceColumn;
        this.impact = builder.impact;
        this.confidence = builder.confidence;
        this.scope = builder.scope;
        this.hops = builder.hops;
        this.batch = builder.batch;
        this.verdict = builder.verdict;
        this.reasonZh = builder.reasonZh;
        this.status = builder.status;
        this.confirmedBy = builder.confirmedBy;
        this.confirmedAt = builder.confirmedAt;
        this.occurredAt = builder.occurredAt;
        this.writeBackAt = builder.writeBackAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 幂等键 {@code sha256(view_name|sql_digest|changed_field)}。 */
    public String idempotencyKey() {
        return idempotencyKey;
    }

    /** 被改动的取数视图名。 */
    public String viewName() {
        return viewName;
    }

    /** 视图方言，首版钉死 Oracle。 */
    public String viewDialect() {
        return viewDialect;
    }

    /** 视图定义的规范化摘要 sha256。 */
    public String sqlDigest() {
        return sqlDigest;
    }

    /** 被改动的列名。 */
    public String changedField() {
        return changedField;
    }

    /** 改动类型。 */
    public ChangeKind changeKind() {
        return changeKind;
    }

    /** 受影响的清单分区。 */
    public String section() {
        return section;
    }

    /** 受影响的清单字段名。 */
    public String listFieldName() {
        return listFieldName;
    }

    /** 受影响字段的来源列（视图内列名，可带限定名前缀）。 */
    public String sourceColumn() {
        return sourceColumn;
    }

    /** 影响类型（实质受影响／疑似波及）。 */
    public ImpactType impact() {
        return impact;
    }

    /** 置信度，取值区间 [0,1]。 */
    public double confidence() {
        return confidence;
    }

    /** 计算所用的口径。 */
    public ImpactScope scope() {
        return scope;
    }

    /** 从改动点到该字段的最短跳数。 */
    public int hops() {
        return hops;
    }

    /** 所属上报批次；样例数据一律用 {@code <EXAMPLE_BATCH>} 占位。 */
    public String batch() {
        return batch;
    }

    /** 归因判定。 */
    public Verdict verdict() {
        return verdict;
    }

    /** 归因理由（中文），逐条可核对。 */
    public String reasonZh() {
        return reasonZh;
    }

    /** 业务状态。 */
    public ImpactStatus status() {
        return status;
    }

    /** 人工确认人；未确认时为空。 */
    public String confirmedBy() {
        return confirmedBy;
    }

    /** 人工确认时间；未确认时为空。 */
    public String confirmedAt() {
        return confirmedAt;
    }

    /** 改动发生时间。 */
    public String occurredAt() {
        return occurredAt;
    }

    /** 回写完成时间；未回写时为空。 */
    public String writeBackAt() {
        return writeBackAt;
    }

    /**
     * 是否允许构造回写请求：必须已由人工确认，且确认人与确认时间齐备。
     * 服务层据此拦截，界面据此置灰，两处独立生效。
     */
    public boolean isWriteBackEligible() {
        return status.isHumanConfirmed()
                && confirmedBy != null && !confirmedBy.isBlank()
                && confirmedAt != null && !confirmedAt.isBlank();
    }

    /** 返回带上新状态与新确认信息的副本（原对象不可变）。 */
    public ImpactRecord withStatus(ImpactStatus newStatus) {
        return toBuilder().status(newStatus).build();
    }

    /** 返回带上人工确认信息的副本。 */
    public ImpactRecord withHumanConfirmation(String confirmedBy, String confirmedAt) {
        return toBuilder().confirmedBy(confirmedBy).confirmedAt(confirmedAt).status(ImpactStatus.CONFIRMED).build();
    }

    /** 返回带上回写完成时间的副本。 */
    public ImpactRecord withWriteBackAt(String writeBackAt) {
        return toBuilder().writeBackAt(writeBackAt).status(ImpactStatus.WRITTEN_BACK).build();
    }

    /** 复制构造器。 */
    public Builder toBuilder() {
        return new Builder()
                .idempotencyKey(idempotencyKey)
                .viewName(viewName)
                .viewDialect(viewDialect)
                .sqlDigest(sqlDigest)
                .changedField(changedField)
                .changeKind(changeKind)
                .section(section)
                .listFieldName(listFieldName)
                .sourceColumn(sourceColumn)
                .impact(impact)
                .confidence(confidence)
                .scope(scope)
                .hops(hops)
                .batch(batch)
                .verdict(verdict)
                .reasonZh(reasonZh)
                .status(status)
                .confirmedBy(confirmedBy)
                .confirmedAt(confirmedAt)
                .occurredAt(occurredAt)
                .writeBackAt(writeBackAt);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ImpactRecord that)) {
            return false;
        }
        return Objects.equals(idempotencyKey, that.idempotencyKey)
                && Objects.equals(viewName, that.viewName)
                && Objects.equals(changedField, that.changedField)
                && Objects.equals(section, that.section)
                && Objects.equals(listFieldName, that.listFieldName)
                && Objects.equals(sourceColumn, that.sourceColumn)
                && impact == that.impact
                && Objects.equals(scope, that.scope)
                && Objects.equals(status, that.status);
    }

    @Override
    public int hashCode() {
        return Objects.hash(idempotencyKey, viewName, changedField, section, listFieldName, sourceColumn, impact, scope, status);
    }

    @Override
    public String toString() {
        return "ImpactRecord{" + viewName + "." + changedField + " -> " + section + "/" + listFieldName
                + " (" + (impact == null ? "?" : impact.labelZh()) + ", " + scope + ", " + status + ")}";
    }

    /** {@link ImpactRecord} 的构造器。 */
    public static final class Builder {

        private String idempotencyKey = "";
        private String viewName = "";
        private String viewDialect = "";
        private String sqlDigest = "";
        private String changedField = "";
        private ChangeKind changeKind;
        private String section = "";
        private String listFieldName = "";
        private String sourceColumn = "";
        private ImpactType impact;
        private double confidence;
        private ImpactScope scope = ImpactScope.STRICT;
        private int hops;
        private String batch = "";
        private Verdict verdict = Verdict.NOT_ATTRIBUTED;
        private String reasonZh = "";
        private ImpactStatus status = ImpactStatus.PARSED;
        private String confirmedBy = "";
        private String confirmedAt = "";
        private String occurredAt = "";
        private String writeBackAt = "";

        private Builder() {
        }

        public Builder idempotencyKey(String value) {
            this.idempotencyKey = nullToEmpty(value);
            return this;
        }

        public Builder viewName(String value) {
            this.viewName = nullToEmpty(value);
            return this;
        }

        public Builder viewDialect(String value) {
            this.viewDialect = nullToEmpty(value);
            return this;
        }

        public Builder sqlDigest(String value) {
            this.sqlDigest = nullToEmpty(value);
            return this;
        }

        public Builder changedField(String value) {
            this.changedField = nullToEmpty(value);
            return this;
        }

        public Builder changeKind(ChangeKind value) {
            this.changeKind = value;
            return this;
        }

        public Builder section(String value) {
            this.section = nullToEmpty(value);
            return this;
        }

        public Builder listFieldName(String value) {
            this.listFieldName = nullToEmpty(value);
            return this;
        }

        public Builder sourceColumn(String value) {
            this.sourceColumn = nullToEmpty(value);
            return this;
        }

        public Builder impact(ImpactType value) {
            this.impact = value;
            return this;
        }

        public Builder confidence(double value) {
            if (value < 0.0d || value > 1.0d || Double.isNaN(value)) {
                throw new IllegalArgumentException("置信度必须落在 [0,1] 区间，收到：" + value);
            }
            this.confidence = value;
            return this;
        }

        public Builder scope(ImpactScope value) {
            this.scope = value;
            return this;
        }

        public Builder hops(int value) {
            if (value < 0) {
                throw new IllegalArgumentException("跳数不可为负，收到：" + value);
            }
            this.hops = value;
            return this;
        }

        public Builder batch(String value) {
            this.batch = nullToEmpty(value);
            return this;
        }

        public Builder verdict(Verdict value) {
            this.verdict = value;
            return this;
        }

        public Builder reasonZh(String value) {
            this.reasonZh = nullToEmpty(value);
            return this;
        }

        public Builder status(ImpactStatus value) {
            this.status = value;
            return this;
        }

        public Builder confirmedBy(String value) {
            this.confirmedBy = nullToEmpty(value);
            return this;
        }

        public Builder confirmedAt(String value) {
            this.confirmedAt = nullToEmpty(value);
            return this;
        }

        public Builder occurredAt(String value) {
            this.occurredAt = nullToEmpty(value);
            return this;
        }

        public Builder writeBackAt(String value) {
            this.writeBackAt = nullToEmpty(value);
            return this;
        }

        public ImpactRecord build() {
            if (viewName.isBlank()) {
                throw new IllegalArgumentException("影响面记录必须带视图名");
            }
            if (changedField.isBlank()) {
                throw new IllegalArgumentException("影响面记录必须带被改动的列名");
            }
            return new ImpactRecord(this);
        }

        private static String nullToEmpty(String value) {
            return value == null ? "" : value;
        }
    }
}
