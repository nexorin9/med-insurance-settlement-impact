package com.chaosforge.medins.sqlparse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 单段视图的解析产物：列级边 + 待人工核对条目 + 结构化错误 + 运行统计。 */
public final class ParseResult {

    private final String viewName;
    private final SqlDialect dialect;
    private final String sqlDigest;
    private final List<ColumnEdge> edges;
    private final List<ManualReviewItem> reviewItems;
    private final List<StructuredError> errors;
    private final ParseReport report;
    private final boolean success;

    public ParseResult(String viewName, SqlDialect dialect, String sqlDigest,
                       List<ColumnEdge> edges, List<ManualReviewItem> reviewItems,
                       List<StructuredError> errors, ParseReport report, boolean success) {
        this.viewName = viewName == null ? "" : viewName;
        this.dialect = dialect == null ? SqlDialect.ORACLE : dialect;
        this.sqlDigest = sqlDigest == null ? "" : sqlDigest;
        this.edges = edges == null ? List.of() : List.copyOf(edges);
        this.reviewItems = reviewItems == null ? List.of() : List.copyOf(reviewItems);
        this.errors = errors == null ? List.of() : List.copyOf(errors);
        this.report = report == null ? new ParseReport() : report;
        this.success = success;
    }

    /** 构造成功结果，自动汇总统计。 */
    public static ParseResult success(String viewName, SqlDialect dialect, String sqlDigest,
                                      List<ColumnEdge> edges, List<ManualReviewItem> reviews) {
        ParseReport report = new ParseReport();
        report.addSuccess(edges == null ? 0 : edges.size());
        report.countEdges(edges);
        report.addReviewItems(reviews);
        return new ParseResult(viewName, dialect, sqlDigest, edges, reviews, List.of(), report, true);
    }

    /** 构造失败结果，自动登记错误与待核对条目。 */
    public static ParseResult failure(String viewName, SqlDialect dialect, String sqlDigest,
                                      StructuredError error, ManualReviewItem review) {
        ParseReport report = new ParseReport();
        report.addFailure(error);
        if (review != null) report.addReviewItem(review);
        List<ManualReviewItem> reviews = review == null ? List.of() : List.of(review);
        List<StructuredError> errors = error == null ? List.of() : List.of(error);
        return new ParseResult(viewName, dialect, sqlDigest, List.of(), reviews, errors, report, false);
    }

    public String viewName() { return viewName; }
    public SqlDialect dialect() { return dialect; }
    public String sqlDigest() { return sqlDigest; }
    public List<ColumnEdge> edges() { return edges; }
    public List<ManualReviewItem> reviewItems() { return reviewItems; }
    public List<StructuredError> errors() { return errors; }
    public ParseReport report() { return report; }
    public boolean success() { return success; }
    public int edgeCount() { return edges.size(); }

    public List<ColumnEdge> edgesOfKind(com.chaosforge.medins.model.EdgeKind kind) {
        List<ColumnEdge> out = new ArrayList<>();
        for (ColumnEdge e : edges) if (e.edgeKind() == kind) out.add(e);
        return Collections.unmodifiableList(out);
    }

    /** CLI parse-view 输出的 JSON 结构（字段顺序稳定，便于 golden 比对）。 */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("view_name", viewName);
        m.put("dialect", dialect.wireName());
        m.put("sql_digest", sqlDigest);
        List<Map<String, Object>> edgeMaps = new ArrayList<>();
        edges.forEach(e -> edgeMaps.add(e.toMap()));
        m.put("edges", edgeMaps);
        List<Map<String, Object>> reviewMaps = new ArrayList<>();
        reviewItems.forEach(r -> reviewMaps.add(r.toMap()));
        m.put("review_items", reviewMaps);
        List<Map<String, Object>> errorMaps = new ArrayList<>();
        errors.forEach(e -> errorMaps.add(e.toMap()));
        m.put("errors", errorMaps);
        m.put("report", report.toMap());
        m.put("success", success);
        return m;
    }
}
