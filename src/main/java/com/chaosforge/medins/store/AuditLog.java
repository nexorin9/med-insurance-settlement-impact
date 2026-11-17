package com.chaosforge.medins.store;

import com.chaosforge.medins.config.SecretRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 不可变审计链（追加式文件，每行一个 JSON）。
 *
 * <p>规则：条目只增不改；撤回不是删除，而是<b>追加一条撤回条目</b>，原条目仍在。
 * 支持按视图 / 批次 / 操作人回查。所有文本字段落盘前统一过 {@link SecretRedactor}，
 * 不出现凭据字面量与患者可识别明细。
 */
public final class AuditLog {

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<AuditEntry> entries = new ArrayList<>();

    public AuditLog(Path file) {
        this.file = file;
        load();
    }

    /** 只增不改：追加一条审计条目；写文件失败退回 stderr，不抛异常掩盖业务结果。 */
    public synchronized void append(AuditEntry e) {
        if (e == null) {
            return;
        }
        entries.add(e);
        try {
            Files.writeString(file, mapper.writeValueAsString(e.toMap()) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException io) {
            System.err.println("[AuditLog] 审计落盘失败，退回 stderr（保留条目）："
                    + SecretRedactor.empty().redact(String.valueOf(io.getMessage())));
            try {
                System.err.println(mapper.writeValueAsString(e.toMap()));
            } catch (IOException ignored) {
                System.err.println(e.toString());
            }
        }
    }

    public synchronized List<AuditEntry> byView(String view) {
        List<AuditEntry> out = new ArrayList<>();
        for (AuditEntry e : entries) {
            if (view != null && view.equals(e.view())) {
                out.add(e);
            }
        }
        return List.copyOf(out);
    }

    public synchronized List<AuditEntry> byBatch(String batch) {
        List<AuditEntry> out = new ArrayList<>();
        for (AuditEntry e : entries) {
            if (batch != null && batch.equals(e.batch())) {
                out.add(e);
            }
        }
        return List.copyOf(out);
    }

    public synchronized List<AuditEntry> byOperator(String operator) {
        List<AuditEntry> out = new ArrayList<>();
        for (AuditEntry e : entries) {
            if (operator != null && operator.equals(e.operator())) {
                out.add(e);
            }
        }
        return List.copyOf(out);
    }

    public synchronized List<AuditEntry> all() {
        return List.copyOf(entries);
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = mapper.readValue(line, Map.class);
                    entries.add(AuditEntry.fromMap(m));
                } catch (IOException badLine) {
                    // 单行损坏不影响整条审计链读取
                    System.err.println("[AuditLog] 跳过损坏行：" + badLine.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("[AuditLog] 读取审计链失败：" + e.getMessage());
        }
    }

    /**
     * 不可变审计条目：谁、何时、哪一列、波及哪几栏、用的是哪一口径、谁确认的、
     * 是否已回写、是否已撤回、动作、状态跃迁、幂等键与备注。
     */
    public static final class AuditEntry {

        private static final SecretRedactor REDACTOR = SecretRedactor.empty();

        private final String at;
        private final String operator;
        private final String action;
        private final String view;
        private final String batch;
        private final String column;
        private final String section;
        private final String listField;
        private final String scope;
        private final String confirmedBy;
        private final boolean writtenBack;
        private final boolean withdrawn;
        private final String idempotencyKey;
        private final String fromStatus;
        private final String toStatus;
        private final String detail;

        private AuditEntry(Builder b) {
            this.at = r(b.at.isEmpty() ? Instant.now().toString() : b.at);
            this.operator = r(b.operator);
            this.action = r(b.action);
            this.view = r(b.view);
            this.batch = r(b.batch);
            this.column = r(b.column);
            this.section = r(b.section);
            this.listField = r(b.listField);
            this.scope = r(b.scope);
            this.confirmedBy = r(b.confirmedBy);
            this.writtenBack = b.writtenBack;
            this.withdrawn = b.withdrawn;
            this.idempotencyKey = r(b.idempotencyKey);
            this.fromStatus = r(b.fromStatus);
            this.toStatus = r(b.toStatus);
            this.detail = r(b.detail);
        }

        public static Builder builder() {
            return new Builder();
        }

        public String at() { return at; }
        public String operator() { return operator; }
        public String action() { return action; }
        public String view() { return view; }
        public String batch() { return batch; }
        public String column() { return column; }
        public String section() { return section; }
        public String listField() { return listField; }
        public String scope() { return scope; }
        public String confirmedBy() { return confirmedBy; }
        public boolean writtenBack() { return writtenBack; }
        public boolean withdrawn() { return withdrawn; }
        public String idempotencyKey() { return idempotencyKey; }
        public String fromStatus() { return fromStatus; }
        public String toStatus() { return toStatus; }
        public String detail() { return detail; }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", at);
            m.put("operator", operator);
            m.put("action", action);
            m.put("view", view);
            m.put("batch", batch);
            m.put("column", column);
            m.put("section", section);
            m.put("list_field", listField);
            m.put("scope", scope);
            m.put("confirmed_by", confirmedBy);
            m.put("written_back", writtenBack);
            m.put("withdrawn", withdrawn);
            m.put("idempotency_key", idempotencyKey);
            m.put("from_status", fromStatus);
            m.put("to_status", toStatus);
            m.put("detail", detail);
            return m;
        }

        public static AuditEntry fromMap(Map<String, Object> m) {
            Builder b = builder()
                    .at(str(m.get("at")))
                    .operator(str(m.get("operator")))
                    .action(str(m.get("action")))
                    .view(str(m.get("view")))
                    .batch(str(m.get("batch")))
                    .column(str(m.get("column")))
                    .section(str(m.get("section")))
                    .listField(str(m.get("list_field")))
                    .scope(str(m.get("scope")))
                    .confirmedBy(str(m.get("confirmed_by")))
                    .writtenBack(bool(m.get("written_back")))
                    .withdrawn(bool(m.get("withdrawn")))
                    .idempotencyKey(str(m.get("idempotency_key")))
                    .fromStatus(str(m.get("from_status")))
                    .toStatus(str(m.get("to_status")))
                    .detail(str(m.get("detail")));
            return b.build();
        }

        @Override
        public String toString() {
            return "AuditEntry{" + at + " " + operator + " " + action + " " + view + "}";
        }

        private static String r(String s) {
            return REDACTOR.redact(s == null ? "" : s);
        }

        private static String str(Object o) {
            return o == null ? "" : String.valueOf(o);
        }

        private static boolean bool(Object o) {
            return o instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(o));
        }

        public static final class Builder {
            private String at = "";
            private String operator = "";
            private String action = "状态跃迁";
            private String view = "";
            private String batch = "";
            private String column = "";
            private String section = "";
            private String listField = "";
            private String scope = "";
            private String confirmedBy = "";
            private boolean writtenBack = false;
            private boolean withdrawn = false;
            private String idempotencyKey = "";
            private String fromStatus = "";
            private String toStatus = "";
            private String detail = "";

            private Builder() { }

            public Builder at(String v) { this.at = v; return this; }
            public Builder operator(String v) { this.operator = v; return this; }
            public Builder action(String v) { this.action = v; return this; }
            public Builder view(String v) { this.view = v; return this; }
            public Builder batch(String v) { this.batch = v; return this; }
            public Builder column(String v) { this.column = v; return this; }
            public Builder section(String v) { this.section = v; return this; }
            public Builder listField(String v) { this.listField = v; return this; }
            public Builder scope(String v) { this.scope = v; return this; }
            public Builder confirmedBy(String v) { this.confirmedBy = v; return this; }
            public Builder writtenBack(boolean v) { this.writtenBack = v; return this; }
            public Builder withdrawn(boolean v) { this.withdrawn = v; return this; }
            public Builder idempotencyKey(String v) { this.idempotencyKey = v; return this; }
            public Builder fromStatus(String v) { this.fromStatus = v; return this; }
            public Builder toStatus(String v) { this.toStatus = v; return this; }
            public Builder detail(String v) { this.detail = v; return this; }

            public AuditEntry build() {
                return new AuditEntry(this);
            }
        }
    }
}
