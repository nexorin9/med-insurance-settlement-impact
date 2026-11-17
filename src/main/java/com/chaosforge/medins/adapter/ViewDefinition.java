package com.chaosforge.medins.adapter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次取数视图定义读取的结果：视图名、方言、SQL 正文、规范化摘要、取数时间、来源标识。
 *
 * <p>{@code sqlDigest} 复用 {@code sqlparse.SqlDigest.digest}，同时承担「是否真变了」与幂等键两个职责；
 * {@code sourceId} 为不含口令的库地址标识，供审计与「图已过期」判断使用。
 */
public final class ViewDefinition {

    private final String viewName;
    private final String dialect;
    private final String sql;
    private final String sqlDigest;
    private final String readAt;
    private final String sourceId;

    public ViewDefinition(String viewName, String dialect, String sql,
                          String sqlDigest, String readAt, String sourceId) {
        this.viewName = viewName == null ? "" : viewName;
        this.dialect = dialect == null ? "" : dialect;
        this.sql = sql == null ? "" : sql;
        this.sqlDigest = sqlDigest == null ? "" : sqlDigest;
        this.readAt = readAt == null ? "" : readAt;
        this.sourceId = sourceId == null ? "" : sourceId;
    }

    public String viewName() { return viewName; }
    public String dialect() { return dialect; }
    public String sql() { return sql; }
    public String sqlDigest() { return sqlDigest; }
    public String readAt() { return readAt; }
    public String sourceId() { return sourceId; }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("view_name", viewName);
        m.put("dialect", dialect);
        m.put("sql_digest", sqlDigest);
        m.put("read_at", readAt);
        m.put("source_id", sourceId);
        // SQL 正文不进摘要 map，避免把大段视图定义写进审计文本
        return m;
    }
}
