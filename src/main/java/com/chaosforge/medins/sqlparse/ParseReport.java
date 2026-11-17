package com.chaosforge.medins.sqlparse;

import com.chaosforge.medins.model.EdgeKind;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 解析运行统计：这一轮解析了多少、失败多少、降级多少、待人工核对多少。 */
public final class ParseReport {

    private int totalSegments;
    private int successSegments;
    private int failedSegments;
    private int degradedSegments;
    private int edgeCount;
    private final List<ManualReviewItem> reviewItems = new ArrayList<>();
    private final List<StructuredError> errors = new ArrayList<>();
    private final EnumMap<EdgeKind, Integer> countsByKind = new EnumMap<>(EdgeKind.class);
    private final Map<String, Integer> degradationReasons = new TreeMap<>();

    public void addSuccess(int edges) {
        totalSegments++;
        successSegments++;
        edgeCount += edges;
    }

    public void addFailure(StructuredError error) {
        totalSegments++;
        failedSegments++;
        if (error != null) errors.add(error);
    }

    public void addDegraded(String reason) {
        degradedSegments++;
        degradationReasons.merge(reason == null ? "unknown" : reason, 1, Integer::sum);
    }

    public void addReviewItem(ManualReviewItem item) {
        if (item != null) reviewItems.add(item);
    }

    public void addReviewItems(Collection<ManualReviewItem> items) {
        if (items != null) items.forEach(this::addReviewItem);
    }

    public void countEdge(EdgeKind kind) {
        if (kind != null) countsByKind.merge(kind, 1, Integer::sum);
    }

    public void countEdges(Collection<ColumnEdge> edges) {
        if (edges != null) edges.forEach(e -> countEdge(e.edgeKind()));
    }

    /** 合并另一份报告（多段视图汇总时使用）。 */
    public void merge(ParseReport other) {
        if (other == null) return;
        totalSegments += other.totalSegments;
        successSegments += other.successSegments;
        failedSegments += other.failedSegments;
        degradedSegments += other.degradedSegments;
        edgeCount += other.edgeCount;
        reviewItems.addAll(other.reviewItems);
        errors.addAll(other.errors);
        other.countsByKind.forEach((k, v) -> countsByKind.merge(k, v, Integer::sum));
        other.degradationReasons.forEach((k, v) -> degradationReasons.merge(k, v, Integer::sum));
    }

    public int totalSegments() { return totalSegments; }
    public int successSegments() { return successSegments; }
    public int failedSegments() { return failedSegments; }
    public int degradedSegments() { return degradedSegments; }
    public int edgeCount() { return edgeCount; }
    public int reviewItemCount() { return reviewItems.size(); }
    public List<ManualReviewItem> reviewItems() { return List.copyOf(reviewItems); }
    public List<StructuredError> errors() { return List.copyOf(errors); }
    public Map<EdgeKind, Integer> countsByKind() { return new EnumMap<>(countsByKind); }
    public Map<String, Integer> degradationReasons() { return new TreeMap<>(degradationReasons); }

    public int countOf(EdgeKind kind) { return countsByKind.getOrDefault(kind, 0); }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total_segments", totalSegments);
        m.put("success_segments", successSegments);
        m.put("failed_segments", failedSegments);
        m.put("degraded_segments", degradedSegments);
        m.put("edge_count", edgeCount);
        m.put("review_item_count", reviewItems.size());
        Map<String, Object> kinds = new LinkedHashMap<>();
        for (EdgeKind k : EdgeKind.values()) kinds.put(k.wireName(), countOf(k));
        m.put("counts_by_kind", kinds);
        m.put("degradation_reasons", new TreeMap<>(degradationReasons));
        List<Map<String, Object>> items = new ArrayList<>();
        reviewItems.forEach(i -> items.add(i.toMap()));
        m.put("review_items", items);
        List<Map<String, Object>> errs = new ArrayList<>();
        errors.forEach(e -> errs.add(e.toMap()));
        m.put("errors", errs);
        return m;
    }
}
