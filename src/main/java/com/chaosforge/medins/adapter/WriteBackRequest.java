package com.chaosforge.medins.adapter;

import com.chaosforge.medins.model.ImpactRecord;
import com.chaosforge.medins.sqlparse.SqlDigest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 台账回写请求体 —— 字段名与 {@code spec.md §2.4} 逐字一致。
 *
 * <p>字段：{@code idempotency_key / view_name / view_dialect / sql_digest / changed_field /
 * change_kind / affected_list_fields[] / affected_batches[] / summary_zh / confirmed_by /
 * confirmed_at / occurred_at}。{@code affected_list_fields[]} 每项含
 * {@code section / field / source_column / impact / confidence / batch}。
 *
 * <p>幂等键按 {@link SqlDigest#idempotencyKey} 计算，同时作为 HTTP {@code Idempotency-Key} 头。
 */
public final class WriteBackRequest {

    private final String idempotencyKey;
    private final String viewName;
    private final String viewDialect;
    private final String sqlDigest;
    private final String changedField;
    private final String changeKind;
    private final List<Map<String, Object>> affectedListFields;
    private final List<String> affectedBatches;
    private final String summaryZh;
    private final String confirmedBy;
    private final String confirmedAt;
    private final String occurredAt;

    private WriteBackRequest(Builder b) {
        this.idempotencyKey = nz(b.idempotencyKey);
        this.viewName = nz(b.viewName);
        this.viewDialect = nz(b.viewDialect);
        this.sqlDigest = nz(b.sqlDigest);
        this.changedField = nz(b.changedField);
        this.changeKind = nz(b.changeKind);
        this.affectedListFields = List.copyOf(b.affectedListFields);
        this.affectedBatches = List.copyOf(b.affectedBatches);
        this.summaryZh = nz(b.summaryZh);
        this.confirmedBy = nz(b.confirmedBy);
        this.confirmedAt = nz(b.confirmedAt);
        this.occurredAt = nz(b.occurredAt);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String idempotencyKey() { return idempotencyKey; }
    public String viewName() { return viewName; }
    public String viewDialect() { return viewDialect; }
    public String sqlDigest() { return sqlDigest; }
    public String changedField() { return changedField; }
    public String changeKind() { return changeKind; }
    public List<Map<String, Object>> affectedListFields() { return affectedListFields; }
    public List<String> affectedBatches() { return affectedBatches; }
    public String summaryZh() { return summaryZh; }
    public String confirmedBy() { return confirmedBy; }
    public String confirmedAt() { return confirmedAt; }
    public String occurredAt() { return occurredAt; }

    /** 按 spec.md §2.4 字段名输出稳定的 Map（顺序固定，便于变更单降级导出比对）。 */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("idempotency_key", idempotencyKey);
        m.put("view_name", viewName);
        m.put("view_dialect", viewDialect);
        m.put("sql_digest", sqlDigest);
        m.put("changed_field", changedField);
        m.put("change_kind", changeKind);
        m.put("affected_list_fields", affectedListFields);
        m.put("affected_batches", affectedBatches);
        m.put("summary_zh", summaryZh);
        m.put("confirmed_by", confirmedBy);
        m.put("confirmed_at", confirmedAt);
        m.put("occurred_at", occurredAt);
        return m;
    }

    public String toJson() {
        try {
            return new ObjectMapper().writeValueAsString(toMap());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("回写请求体序列化失败", e);
        }
    }

    /**
     * 由已人工确认的影响面记录拼装请求体：幂等键、批次、清单字段等逐项对齐 §2.4。
     * 仅供已确认记录使用；缺确认信息由 {@link WriteBackGuard} 在服务层拦截。
     */
    public static WriteBackRequest fromRecords(List<ImpactRecord> records, String summaryZh) {
        if (records == null || records.isEmpty()) {
            throw new IllegalArgumentException("回写请求必须由至少一条影响面记录构造");
        }
        ImpactRecord first = records.get(0);
        List<Map<String, Object>> fields = new ArrayList<>();
        Set<String> batches = new LinkedHashSet<>();
        for (ImpactRecord r : records) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("section", r.section());
            item.put("field", r.listFieldName());
            item.put("source_column", r.sourceColumn());
            item.put("impact", r.impact() == null ? "" : r.impact().wireName());
            item.put("confidence", r.confidence());
            item.put("batch", r.batch());
            fields.add(item);
            batches.add(r.batch());
        }
        String occurredAt = first.occurredAt() == null || first.occurredAt().isBlank()
                ? Instant.now().toString() : first.occurredAt();
        return builder()
                .idempotencyKey(SqlDigest.idempotencyKey(first.viewName(), first.sqlDigest(), first.changedField()))
                .viewName(first.viewName())
                .viewDialect(first.viewDialect())
                .sqlDigest(first.sqlDigest())
                .changedField(first.changedField())
                .changeKind(first.changeKind() == null ? "" : first.changeKind().wireName())
                .affectedListFields(fields)
                .affectedBatches(new ArrayList<>(batches))
                .summaryZh(summaryZh == null ? "" : summaryZh)
                .confirmedBy(first.confirmedBy())
                .confirmedAt(first.confirmedAt())
                .occurredAt(occurredAt)
                .build();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** {@link WriteBackRequest} 构造器。 */
    public static final class Builder {
        private String idempotencyKey = "";
        private String viewName = "";
        private String viewDialect = "";
        private String sqlDigest = "";
        private String changedField = "";
        private String changeKind = "";
        private List<Map<String, Object>> affectedListFields = new ArrayList<>();
        private List<String> affectedBatches = new ArrayList<>();
        private String summaryZh = "";
        private String confirmedBy = "";
        private String confirmedAt = "";
        private String occurredAt = "";

        private Builder() { }

        public Builder idempotencyKey(String v) { this.idempotencyKey = v; return this; }
        public Builder viewName(String v) { this.viewName = v; return this; }
        public Builder viewDialect(String v) { this.viewDialect = v; return this; }
        public Builder sqlDigest(String v) { this.sqlDigest = v; return this; }
        public Builder changedField(String v) { this.changedField = v; return this; }
        public Builder changeKind(String v) { this.changeKind = v; return this; }
        public Builder affectedListFields(List<Map<String, Object>> v) {
            this.affectedListFields = v == null ? new ArrayList<>() : new ArrayList<>(v);
            return this;
        }
        public Builder addAffectedListField(String section, String field, String sourceColumn,
                                            String impact, double confidence, String batch) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("section", section);
            item.put("field", field);
            item.put("source_column", sourceColumn);
            item.put("impact", impact);
            item.put("confidence", confidence);
            item.put("batch", batch);
            this.affectedListFields.add(item);
            return this;
        }
        public Builder affectedBatches(List<String> v) {
            this.affectedBatches = v == null ? new ArrayList<>() : new ArrayList<>(v);
            return this;
        }
        public Builder summaryZh(String v) { this.summaryZh = v; return this; }
        public Builder confirmedBy(String v) { this.confirmedBy = v; return this; }
        public Builder confirmedAt(String v) { this.confirmedAt = v; return this; }
        public Builder occurredAt(String v) { this.occurredAt = v; return this; }

        public WriteBackRequest build() {
            if (viewName == null || viewName.isBlank()) {
                throw new IllegalArgumentException("回写请求必须带视图名");
            }
            return new WriteBackRequest(this);
        }
    }
}
