package com.chaosforge.medins.sqlparse;

import com.chaosforge.medins.model.EdgeKind;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 列级边 —— 解析器最小产物：视图的某个输出列，以何种方式读取了哪张表的哪一列。
 *
 * <p>四类边（直取 / 派生 / 聚合 / 过滤）与 kind 专属元数据统一装在这一个对象里，
 * 供血缘图、影响判定、UI 详情与导出四处消费，避免每个环节各自定义一套结构。
 * 对象不可变，构造走 {@link Builder}。
 */
public final class ColumnEdge {

    private final String viewName;
    private final String outputColumn;
    private final String sourceTable;
    private final String sourceColumn;
    private final EdgeKind edgeKind;
    private final String expression;
    private final String aggregateFunction;
    private final boolean distinct;
    private final String conditionPosition;
    private final String joinType;
    private final int branchIndex;
    private final int line;
    private final int column;
    private final boolean unresolved;
    private final boolean ambiguous;
    private final double confidence;

    private ColumnEdge(Builder b) {
        this.viewName = b.viewName;
        this.outputColumn = b.outputColumn;
        this.sourceTable = b.sourceTable;
        this.sourceColumn = b.sourceColumn;
        this.edgeKind = b.edgeKind;
        this.expression = b.expression;
        this.aggregateFunction = b.aggregateFunction;
        this.distinct = b.distinct;
        this.conditionPosition = b.conditionPosition;
        this.joinType = b.joinType;
        this.branchIndex = b.branchIndex;
        this.line = b.line;
        this.column = b.column;
        this.unresolved = b.unresolved;
        this.ambiguous = b.ambiguous;
        this.confidence = b.confidence;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String viewName() { return viewName; }
    public String outputColumn() { return outputColumn; }
    public String sourceTable() { return sourceTable; }
    public String sourceColumn() { return sourceColumn; }
    public EdgeKind edgeKind() { return edgeKind; }
    public String expression() { return expression; }
    public String aggregateFunction() { return aggregateFunction; }
    public boolean distinct() { return distinct; }
    public String conditionPosition() { return conditionPosition; }
    public String joinType() { return joinType; }
    public int branchIndex() { return branchIndex; }
    public int line() { return line; }
    public int column() { return column; }
    public boolean unresolved() { return unresolved; }
    public boolean ambiguous() { return ambiguous; }
    public double confidence() { return confidence; }

    /** 供 JSON 输出与 golden 快照比对的稳定字段顺序。 */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("output_column", outputColumn);
        m.put("source_table", sourceTable);
        m.put("source_column", sourceColumn);
        m.put("edge_kind", edgeKind == null ? "" : edgeKind.wireName());
        m.put("view_name", viewName);
        m.put("expression", expression);
        m.put("aggregate_function", aggregateFunction);
        m.put("distinct", distinct);
        m.put("condition_position", conditionPosition);
        m.put("join_type", joinType);
        m.put("branch_index", branchIndex);
        m.put("line", line);
        m.put("column", column);
        m.put("unresolved", unresolved);
        m.put("ambiguous", ambiguous);
        m.put("confidence", confidence);
        return m;
    }

    /** 从 JSON 映射还原（容忍缺省字段，供快照与 CLI 回读使用）。 */
    public static ColumnEdge fromMap(Map<String, Object> m) {
        return builder()
                .viewName(str(m.get("view_name")))
                .outputColumn(str(m.get("output_column")))
                .sourceTable(str(m.get("source_table")))
                .sourceColumn(str(m.get("source_column")))
                .edgeKind(EdgeKind.fromWireName(str(m.get("edge_kind"))))
                .expression(str(m.get("expression")))
                .aggregateFunction(str(m.get("aggregate_function")))
                .distinct(Boolean.TRUE.equals(m.get("distinct")))
                .conditionPosition(str(m.get("condition_position")))
                .joinType(str(m.get("join_type")))
                .branchIndex(num(m.get("branch_index"), 0))
                .line(num(m.get("line"), 0))
                .column(num(m.get("column"), 0))
                .unresolved(Boolean.TRUE.equals(m.get("unresolved")))
                .ambiguous(Boolean.TRUE.equals(m.get("ambiguous")))
                .confidence(dbl(m.get("confidence"), 1.0d))
                .build();
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }
    private static int num(Object o, int d) { return o instanceof Number n ? n.intValue() : d; }
    private static double dbl(Object o, double d) { return o instanceof Number n ? n.doubleValue() : d; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ColumnEdge that)) return false;
        return branchIndex == that.branchIndex && line == that.line && column == that.column
                && Objects.equals(viewName, that.viewName)
                && Objects.equals(outputColumn, that.outputColumn)
                && Objects.equals(sourceTable, that.sourceTable)
                && Objects.equals(sourceColumn, that.sourceColumn)
                && edgeKind == that.edgeKind
                && Objects.equals(expression, that.expression)
                && Objects.equals(aggregateFunction, that.aggregateFunction)
                && distinct == that.distinct
                && Objects.equals(conditionPosition, that.conditionPosition)
                && Objects.equals(joinType, that.joinType)
                && unresolved == that.unresolved
                && ambiguous == that.ambiguous;
    }

    @Override
    public int hashCode() {
        return Objects.hash(viewName, outputColumn, sourceTable, sourceColumn, edgeKind, expression,
                aggregateFunction, distinct, conditionPosition, joinType, branchIndex, line, column,
                unresolved, ambiguous);
    }

    @Override
    public String toString() {
        return viewName + "." + outputColumn + " <- " + sourceTable + "." + sourceColumn
                + " [" + (edgeKind == null ? "?" : edgeKind.wireName()) + "]";
    }

    /** {@link ColumnEdge} 构造器。 */
    public static final class Builder {
        private String viewName = "";
        private String outputColumn = "";
        private String sourceTable = "";
        private String sourceColumn = "";
        private EdgeKind edgeKind = EdgeKind.DIRECT;
        private String expression = "";
        private String aggregateFunction = "";
        private boolean distinct;
        private String conditionPosition = "";
        private String joinType = "";
        private int branchIndex;
        private int line;
        private int column;
        private boolean unresolved;
        private boolean ambiguous;
        private double confidence = 1.0d;

        private Builder() { }

        public Builder viewName(String v) { this.viewName = nullToEmpty(v); return this; }
        public Builder outputColumn(String v) { this.outputColumn = nullToEmpty(v); return this; }
        public Builder sourceTable(String v) { this.sourceTable = nullToEmpty(v); return this; }
        public Builder sourceColumn(String v) { this.sourceColumn = nullToEmpty(v); return this; }
        public Builder edgeKind(EdgeKind v) { this.edgeKind = v == null ? EdgeKind.DIRECT : v; return this; }
        public Builder expression(String v) { this.expression = nullToEmpty(v); return this; }
        public Builder aggregateFunction(String v) { this.aggregateFunction = nullToEmpty(v); return this; }
        public Builder distinct(boolean v) { this.distinct = v; return this; }
        public Builder conditionPosition(String v) { this.conditionPosition = nullToEmpty(v); return this; }
        public Builder joinType(String v) { this.joinType = nullToEmpty(v); return this; }
        public Builder branchIndex(int v) { this.branchIndex = v; return this; }
        public Builder line(int v) { this.line = v; return this; }
        public Builder column(int v) { this.column = v; return this; }
        public Builder unresolved(boolean v) { this.unresolved = v; return this; }
        public Builder ambiguous(boolean v) { this.ambiguous = v; return this; }
        public Builder confidence(double v) {
            if (v < 0 || v > 1 || Double.isNaN(v)) throw new IllegalArgumentException("置信度必须落在 [0,1]");
            this.confidence = v;
            return this;
        }

        public ColumnEdge build() {
            if (outputColumn.isBlank()) throw new IllegalArgumentException("列级边必须带输出列名");
            return new ColumnEdge(this);
        }

        private static String nullToEmpty(String v) { return v == null ? "" : v; }
    }
}
