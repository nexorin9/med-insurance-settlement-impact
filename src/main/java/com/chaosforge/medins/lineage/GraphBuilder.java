package com.chaosforge.medins.lineage;

import com.chaosforge.medins.model.EdgeKind;
import com.chaosforge.medins.model.FieldKey;
import com.chaosforge.medins.sqlparse.ColumnEdge;
import com.chaosforge.medins.sqlparse.ParseResult;
import com.chaosforge.medins.sqlparse.SqlViewParser;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 图构建入口：把多段视图解析产出的列级边，合成一张跨视图字段级图。
 *
 * <p>字段键统一为「视图名/表名.列名」的大写规范形式（{@link FieldKey#of} 负责规范化）。
 * 当某段视图的 sourceTable 恰好是另一段视图（或 CTE）的名字时，from 节点就是那段视图的输出列，
 * 于是自然形成 A.COL -> B.COL 的跨视图消费链，而不会把 B 的列直接连到底层表；
 * 不同视图的同名列因视图名不同而成为不同节点，不误合并。
 *
 * <p>同一对 (from,to) 的多条不同 edge_kind 边由 {@link FieldLineageGraph#addEdge} 合并去重。
 */
public final class GraphBuilder {

    private GraphBuilder() { }

    /** 由多段解析结果构图；跳过失败段、未解析（SELECT *）边与缺列名的边。 */
    public static FieldLineageGraph build(List<ParseResult> results) {
        FieldLineageGraph graph = new FieldLineageGraph();
        if (results == null) {
            return graph;
        }
        for (ParseResult r : results) {
            if (r == null) {
                continue;
            }
            for (ColumnEdge e : r.edges()) {
                if (e == null || e.unresolved()) {
                    continue; // SELECT * 等未解析边不进入字段级图
                }
                String srcTable = nz(e.sourceTable());
                String srcColumn = nz(e.sourceColumn());
                String outColumn = nz(e.outputColumn());
                String view = nz(e.viewName().isEmpty() ? r.viewName() : e.viewName());
                if (srcTable.isEmpty() || srcColumn.isEmpty() || outColumn.isEmpty() || view.isEmpty()) {
                    continue; // 缺限定名的边无法构成规范字段键，跳过而不是臆造
                }
                FieldKey from = FieldKey.of(srcTable, srcColumn);
                FieldKey to = FieldKey.of(view, outColumn);
                EdgeKind kind = e.edgeKind() == null ? EdgeKind.DIRECT : e.edgeKind();
                graph.addEdge(from, to, kind, view);
            }
        }
        return graph;
    }

    /** 由视图名 -> SQL 文本构图；内部调用契约层 SqlViewParser.parseAll（签名按 §3.1 固定，保持输入顺序）。 */
    public static FieldLineageGraph build(Map<String, String> viewSqls) {
        Objects.requireNonNull(viewSqls, "viewSqls");
        List<ParseResult> results = new SqlViewParser().parseAll(viewSqls);
        return build(results);
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }
}
