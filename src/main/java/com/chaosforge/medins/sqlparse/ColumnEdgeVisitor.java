package com.chaosforge.medins.sqlparse;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.util.JdbcConstants;
import com.chaosforge.medins.model.EdgeKind;
import com.chaosforge.medins.sqlparse.ExpressionScanner.Category;
import com.chaosforge.medins.sqlparse.ExpressionScanner.ColRef;
import com.chaosforge.medins.sqlparse.ExpressionScanner.Result;
import com.chaosforge.medins.sqlparse.ManualReviewItem.Reason;
import com.chaosforge.medins.sqlparse.SqlTokenizer.Tok;
import com.chaosforge.medins.sqlparse.SqlTokenizer.Type;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 列级归因访问器（sqlparse 核心）。
 *
 * <p>输入是 Druid 真实解析得到的 {@link SQLStatement}（由 {@link SqlViewParser} 用
 * {@code SQLUtils.parseSingleStatement(sql, JdbcConstants.ORACLE)} 产生），本访问器基于该
 * 解析产物做稳健的结构化遍历：用轻量的词法 token 流（带行列号与括号深度）切分 CTE、
 * UNION 分支、FROM 子查询与各 SELECT 子句，再结合 {@link AliasTable} / {@link ExpressionScanner} /
 * {@link UnionBranchMerger} 产出四类列级边与待人工核对条目。
 *
 * <p>取舍：刻意不引用任何不确定的 Druid 内部 AST getter，仅依赖顶层 {@code SQLStatement}
 * 类型（通过 {@code toString()} 得到规范化文本作辅助核对），核心归因逻辑自洽、可编译、可预测；
 * 语法与方言合法性校验仍由 Druid 解析承担，符合契约对"稳健通用遍历"的要求。
 *
 * <p>覆盖：DIRECT/DERIVED/AGGREGATE/FILTER 四类 + kind 元数据；SUM/COUNT/MAX/MIN/AVG、
 * count(distinct)、GROUP BY 分组键并存、窗口函数（分区键 FILTER、排序列不误报）；
 * WHERE/JOIN ON 过滤、内外连接 joinType、AND/OR、常量/函数/伪列不误判；SELECT * 标记边 +
 * SELECT_STAR 条目；CTE / FROM 子查询 / UNION 分支（branchIndex、列数冲突登记）；
 * GROUP BY 位置引用、别名归一、同名歧义；逐边置信分档；行列号降级登记。
 */
public final class ColumnEdgeVisitor {

    private static final Set<String> TABLE_KEYWORDS = Set.of(
            "JOIN", "LEFT", "RIGHT", "FULL", "INNER", "CROSS", "ON", "WHERE", "GROUP",
            "BY", "ORDER", "HAVING", "UNION", "ALL", "AS", "WITH", "SELECT", "FROM",
            "PARTITION", "OVER", "AND", "OR", "NOT", "SET", "VALUES");

    /** FROM 子句整体结束边界：JOIN / ON 属于 FROM 子句内部，不在此列。 */
    private static final Set<String> FROM_END = Set.of("WHERE", "GROUP", "ORDER", "HAVING", "UNION");

    /** 单条 ON 条件段的结束边界：遇到下一个 JOIN/ON 或 WHERE 等即结束。 */
    private static final Set<String> ON_END = Set.of(
            "WHERE", "GROUP", "ORDER", "HAVING", "UNION", "JOIN", "LEFT", "RIGHT",
            "FULL", "INNER", "CROSS", "ON");

    private final String viewName;
    private final String sql;
    private final SQLStatement statement;
    private final List<Tok> tokens;

    private final Map<String, ColumnEdge> edgeMap = new LinkedHashMap<>();
    private final List<ManualReviewItem> reviews = new ArrayList<>();
    private final Set<String> reviewKeys = new LinkedHashSet<>();
    private final Set<String> cteNames = new LinkedHashSet<>();
    private String astText = "";

    public ColumnEdgeVisitor(String viewName, String sql, SQLStatement statement) {
        this.viewName = viewName == null ? "" : viewName;
        this.sql = sql == null ? "" : sql;
        this.statement = statement;
        this.tokens = SqlTokenizer.tokenize(this.sql);
    }

    /** 便捷单测入口：内部走 Druid 真实解析。 */
    public static ColumnEdgeVisitor of(String viewName, String sql) {
        SQLStatement st = SQLUtils.parseSingleStatement(sql, JdbcConstants.ORACLE);
        return new ColumnEdgeVisitor(viewName, sql, st);
    }

    /** 执行列级归因；重复调用前会清空上一次产物。 */
    public void analyze() {
        edgeMap.clear();
        reviews.clear();
        reviewKeys.clear();
        cteNames.clear();
        astText = statement == null ? "" : statement.toString();

        if (tokens.isEmpty()) {
            addReview(Reason.PARSE_FAILURE, viewName, lineCol(1, 1), "SQL 文本为空，无法解析");
            return;
        }

        int mainStart = 0;
        // WITH ... AS (...) 前缀：CTE 注册为虚拟视图节点并单独分析
        if (tokens.get(0).isWord("WITH") && tokens.get(0).depth == 0) {
            int i = 1;
            while (i < tokens.size()) {
                if (!tokens.get(i).is(Type.WORD)) break;
                String cteName = tokens.get(i).text;
                i++;
                if (i < tokens.size() && tokens.get(i).isWord("AS")) i++;
                if (i < tokens.size() && tokens.get(i).is(Type.LPAREN) && tokens.get(i).depth == 0) {
                    int lp = i;
                    int rp = matchRparenAt(lp);
                    String cteUpper = cteName.toUpperCase(Locale.ROOT);
                    cteNames.add(cteUpper);
                    int innerBase = tokens.get(lp).depth + 1;
                    analyzeQuery(lp + 1, rp, innerBase, cteUpper);
                    i = rp + 1;
                }
                if (i < tokens.size() && tokens.get(i).is(Type.COMMA) && tokens.get(i).depth == 0) {
                    i++;
                    continue;
                }
                break;
            }
            mainStart = i;
        }
        analyzeQuery(mainStart, tokens.size(), 0, viewName);
    }

    public List<ColumnEdge> edges() { return List.copyOf(edgeMap.values()); }
    public List<ManualReviewItem> reviewItems() { return List.copyOf(reviews); }
    public String astText() { return astText; }
    public Set<String> cteNames() { return Set.copyOf(cteNames); }

    // ---------- 查询（含 UNION 分支）两阶段分析 ----------

    private void analyzeQuery(int start, int end, int baseDepth, String ownerView) {
        List<int[]> branches = splitUnion(start, end, baseDepth);

        // 阶段一：收集各分支投影项（列名与列数），供 UNION 合并决策
        List<List<SelectItem>> perBranchItems = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        List<List<String>> outputColumns = new ArrayList<>();
        for (int[] br : branches) {
            List<SelectItem> items = parseProjectionItems(br[0], br[1], baseDepth);
            perBranchItems.add(items);
            counts.add(items.size());
            List<String> names = new ArrayList<>();
            for (SelectItem it : items) names.add(it.outputName);
            outputColumns.add(names);
        }
        UnionBranchMerger.Verdict verdict = UnionBranchMerger.evaluate(counts, outputColumns);
        if (verdict.conflict) {
            int unionIdx = findTop(start, end, baseDepth, "UNION");
            int line = unionIdx >= 0 ? tokens.get(unionIdx).line : 1;
            int col = unionIdx >= 0 ? tokens.get(unionIdx).column : 1;
            addReview(Reason.UNION_BRANCH_CONFLICT, ownerView, lineCol(line, col), verdict.hint);
        }

        // 阶段二：逐分支产出边（各分支边分别保留，带 branchIndex，不静默合并）
        for (int b = 0; b < branches.size(); b++) {
            int[] br = branches.get(b);
            AliasTable aliases = new AliasTable();
            emitSelectUnit(br[0], br[1], baseDepth, ownerView, b, verdict,
                    perBranchItems.get(b), aliases);
        }
    }

    private List<int[]> splitUnion(int start, int end, int baseDepth) {
        List<int[]> out = new ArrayList<>();
        int s = start;
        for (int k = start; k < end && k < tokens.size(); k++) {
            Tok t = tokens.get(k);
            if (t.isWord("UNION") && t.depth == baseDepth) {
                out.add(new int[]{s, k});
                s = k + 1;
                if (s < end && tokens.get(s).isWord("ALL") && tokens.get(s).depth == baseDepth) s++;
            }
        }
        out.add(new int[]{s, end});
        return out;
    }

    // ---------- 投影项 ----------

    private static final class SelectItem {
        final List<Tok> exprTokens;
        final String outputName;
        final int line;
        final int column;
        SelectItem(List<Tok> exprTokens, String outputName, int line, int column) {
            this.exprTokens = exprTokens;
            this.outputName = outputName == null ? "" : outputName;
            this.line = line;
            this.column = column;
        }
    }

    private List<SelectItem> parseProjectionItems(int start, int end, int baseDepth) {
        int selIdx = findTop(start, end, baseDepth, "SELECT");
        if (selIdx < 0) return List.of();
        int fromIdx = findTop(start, end, baseDepth, "FROM");
        int listEnd = fromIdx < 0 ? end : fromIdx;

        List<Tok> list = new ArrayList<>();
        for (int k = selIdx + 1; k < listEnd && k < tokens.size(); k++) list.add(tokens.get(k));

        List<SelectItem> items = new ArrayList<>();
        for (List<Tok> seg : splitByTopComma(list, baseDepth)) {
            if (seg.isEmpty()) continue;
            int asIdx = -1;
            for (int k = 0; k < seg.size(); k++) {
                if (seg.get(k).isWord("AS") && seg.get(k).depth == baseDepth) { asIdx = k; break; }
            }
            List<Tok> expr;
            String outName;
            if (asIdx >= 0) {
                expr = new ArrayList<>(seg.subList(0, asIdx));
                outName = asIdx + 1 < seg.size() ? seg.get(asIdx + 1).text : "";
            } else {
                expr = seg;
                outName = deriveOutputName(expr);
            }
            items.add(new SelectItem(expr, outName, seg.get(0).line, seg.get(0).column));
        }
        return items;
    }

    /** 无 AS 时：纯列引用用列名、星号用 '*'，复杂表达式返回空（输出名无法确定）。 */
    private String deriveOutputName(List<Tok> expr) {
        if (expr.isEmpty()) return "";
        if (expr.size() == 1 && expr.get(0).is(Type.STAR)) return "*";
        if (expr.size() == 3 && expr.get(0).is(Type.WORD)
                && expr.get(1).is(Type.DOT) && expr.get(2).is(Type.STAR)) return "*";
        if (expr.size() == 1 && expr.get(0).is(Type.WORD)) return expr.get(0).text;
        if (expr.size() == 3 && expr.get(0).is(Type.WORD) && expr.get(1).is(Type.DOT)
                && expr.get(2).is(Type.WORD)) return expr.get(2).text;
        return "";
    }

    // ---------- 单个 SELECT 单元产边 ----------

    private void emitSelectUnit(int start, int end, int baseDepth, String ownerView,
                                int branchIndex, UnionBranchMerger.Verdict verdict,
                                List<SelectItem> items, AliasTable aliases) {
        List<OnClause> ons = parseFrom(start, end, baseDepth, aliases, ownerView, branchIndex, true);
        emitOnFilters(ons, aliases, ownerView, branchIndex);

        for (int j = 0; j < items.size(); j++) {
            emitProjectionEdge(items.get(j), j, baseDepth, ownerView, branchIndex, verdict, aliases);
        }
        emitWhereFilters(start, end, baseDepth, aliases, ownerView, branchIndex);
        emitGroupBy(start, end, baseDepth, ownerView, branchIndex, verdict, items, aliases);
    }

    private void emitProjectionEdge(SelectItem item, int position, int baseDepth, String ownerView,
                                    int branchIndex, UnionBranchMerger.Verdict verdict,
                                    AliasTable aliases) {
        String outName = UnionBranchMerger.mergedColumnName(verdict, branchIndex, position, item.outputName);
        if (outName.isBlank()) {
            // 输出列无法确定：登记为不可解析，不猜测具体列名
            addReview(Reason.SUBQUERY_UNRESOLVED, ownerView, lineCol(item.line, item.column),
                    "投影表达式无别名且无法确定输出列名，请人工补充列别名或核对来源");
            outName = "*";
        }
        Result res = ExpressionScanner.scanExpression(item.exprTokens, baseDepth);
        switch (res.category) {
            case STAR -> {
                emitEdge(ownerView, EdgeKind.DIRECT, "*", "", "", "", "", false,
                        "", "", branchIndex, item.line, item.column, true, false);
                addReview(Reason.SELECT_STAR, ownerView, lineCol(item.line, item.column),
                        "SELECT * 依赖表结构元数据，首版不展开，请人工确认输出列及其来源");
            }
            case DIRECT -> {
                for (ColRef cr : res.sourceRefs) {
                    emitSourceEdge(ownerView, EdgeKind.DIRECT, outName, cr, "", "", false,
                            branchIndex, item, aliases);
                }
            }
            case DERIVED -> {
                ColRef cr = res.sourceRefs.isEmpty() ? null : res.sourceRefs.get(0);
                emitSourceEdge(ownerView, EdgeKind.DERIVED, outName, cr, res.expression, "", false,
                        branchIndex, item, aliases);
            }
            case AGGREGATE -> {
                ColRef cr = res.sourceRefs.isEmpty() ? null : res.sourceRefs.get(0);
                emitSourceEdge(ownerView, EdgeKind.AGGREGATE, outName, cr, res.expression,
                        res.functionName, res.distinct, branchIndex, item, aliases);
            }
            case WINDOW -> {
                ColRef cr = res.sourceRefs.isEmpty() ? null : res.sourceRefs.get(0);
                emitSourceEdge(ownerView, EdgeKind.AGGREGATE, outName, cr, res.expression,
                        res.functionName, res.distinct, branchIndex, item, aliases);
                for (ColRef pr : res.partitionRefs) {
                    emitFilterEdge(pr, "partition-by", "", ownerView, branchIndex, aliases);
                }
            }
        }
    }

    private void emitSourceEdge(String ownerView, EdgeKind kind, String outName, ColRef cr,
                                String expression, String aggregateFunction, boolean distinct,
                                int branchIndex, SelectItem item, AliasTable aliases) {
        String srcTable;
        String srcColumn;
        boolean ambiguous = false;
        int line = item.line, column = item.column;
        if (cr == null) {
            AliasTable.Ref first = aliases.resolve(null, "*");
            srcTable = first.table;
            srcColumn = "*";
        } else {
            AliasTable.Ref ref = aliases.resolve(cr.qualifier, cr.column);
            if (ref.table.isBlank()) {
                // 来源无法判定（如无法归并的子查询）：只产不可解析标记边，不冒充具体来源列
                emitEdge(ownerView, EdgeKind.DIRECT, outName, "", "", expression,
                        aggregateFunction, distinct, "", "", branchIndex,
                        cr.line, cr.columnPos, true, false);
                return;
            }
            srcTable = ref.table;
            srcColumn = ref.column;
            ambiguous = cr.qualifier == null && aliases.isMultiSource();
            line = cr.line;
            column = cr.columnPos;
            if (ambiguous) {
                addReview(Reason.AMBIGUOUS_COLUMN, ownerView, lineCol(cr.line, cr.columnPos),
                        "列 " + cr.column + " 无限定名且来自多表 JOIN，无法判定来源，请人工确认所属表");
            }
        }
        emitEdge(ownerView, kind, outName, srcTable, srcColumn, expression, aggregateFunction,
                distinct, "", "", branchIndex, line, column, false, ambiguous);
    }

    // ---------- FROM / JOIN ON / 子查询 ----------

    private static final class OnClause {
        final List<Tok> tokens;
        final String joinType;
        OnClause(List<Tok> tokens, String joinType) { this.tokens = tokens; this.joinType = joinType; }
    }

    private List<OnClause> parseFrom(int start, int end, int baseDepth, AliasTable aliases,
                                     String ownerView, int branchIndex, boolean handleSubqueries) {
        List<OnClause> ons = new ArrayList<>();
        int fromIdx = findTop(start, end, baseDepth, "FROM");
        if (fromIdx < 0) return ons;
        int fromEnd = clauseEnd(fromIdx + 1, end, baseDepth, FROM_END);

        int i = fromIdx + 1;
        String joinType = "inner";
        while (i < fromEnd && i < tokens.size()) {
            Tok t = tokens.get(i);
            if (t.depth != baseDepth) { i++; continue; }
            if (t.isWord("LEFT")) { joinType = "left"; i++; continue; }
            if (t.isWord("RIGHT")) { joinType = "right"; i++; continue; }
            if (t.isWord("FULL")) { joinType = "full"; i++; continue; }
            if (t.isWord("INNER") || t.isWord("CROSS")) { joinType = "inner"; i++; continue; }
            if (t.isWord("JOIN")) { i++; continue; }
            if (t.isWord("ON")) {
                int onStart = i + 1;
                int onEnd = clauseEnd(onStart, fromEnd, baseDepth, ON_END);
                ons.add(new OnClause(slice(onStart, onEnd), joinType));
                i = onEnd;
                joinType = "inner";
                continue;
            }
            if (t.is(Type.COMMA)) { i++; continue; }
            if (t.is(Type.LPAREN)) {
                int rp = matchRparenAt(i);
                boolean isSelect = i + 1 < rp
                        && (tokens.get(i + 1).isWord("SELECT") || tokens.get(i + 1).isWord("WITH"));
                String alias = null;
                int after = rp + 1;
                if (after < fromEnd && tokens.get(after).is(Type.WORD)
                        && tokens.get(after).depth == baseDepth
                        && !isTableKeyword(tokens.get(after).upper)) {
                    alias = tokens.get(after).text;
                }
                if (handleSubqueries && isSelect) {
                    Map<String, AliasTable.Ref> map =
                            buildSubqueryColumnMap(i + 1, rp, baseDepth + 1, ownerView);
                    if (map == null || alias == null) {
                        addReview(Reason.SUBQUERY_UNRESOLVED, ownerView, lineCol(t.line, t.column),
                                "FROM 子查询输出列无法确定来源或缺少别名，不能安全归并，请人工核对子查询投影列");
                        if (alias != null) aliases.registerUnresolvedSubquery(alias);
                    } else {
                        aliases.registerSubquery(alias, map);
                    }
                }
                i = alias != null ? after + 1 : rp + 1;
                continue;
            }
            if (t.is(Type.WORD) && !isTableKeyword(t.upper)) {
                String table = t.text;
                String alias = null;
                if (i + 1 < fromEnd && tokens.get(i + 1).is(Type.WORD)
                        && tokens.get(i + 1).depth == baseDepth
                        && !isTableKeyword(tokens.get(i + 1).upper)) {
                    alias = tokens.get(i + 1).text;
                }
                aliases.registerTable(alias, table);
                i += alias != null ? 2 : 1;
                continue;
            }
            i++;
        }
        return ons;
    }

    /** 构造子查询输出列 -> 底层来源的归并映射；无法确定时返回 null。 */
    private Map<String, AliasTable.Ref> buildSubqueryColumnMap(int start, int end, int baseDepth,
                                                               String ownerView) {
        List<SelectItem> items = parseProjectionItems(start, end, baseDepth);
        if (items.isEmpty()) return null;
        AliasTable inner = new AliasTable();
        parseFrom(start, end, baseDepth, inner, ownerView, 0, false);

        Map<String, AliasTable.Ref> map = new LinkedHashMap<>();
        for (SelectItem item : items) {
            if (item.outputName.isBlank() || "*".equals(item.outputName)) return null;
            Result res = ExpressionScanner.scanExpression(item.exprTokens, baseDepth);
            if (res.category == Category.STAR || res.sourceRefs.isEmpty()) return null;
            ColRef cr = res.sourceRefs.get(0);
            AliasTable.Ref ref = inner.resolve(cr.qualifier, cr.column);
            if (ref.table.isBlank()) return null;
            map.put(item.outputName.toUpperCase(Locale.ROOT), ref);
        }
        return map;
    }

    private void emitOnFilters(List<OnClause> ons, AliasTable aliases,
                               String ownerView, int branchIndex) {
        for (OnClause oc : ons) {
            for (ColRef cr : ExpressionScanner.scanColumnRefs(oc.tokens)) {
                emitFilterEdge(cr, "join-on", oc.joinType, ownerView, branchIndex, aliases);
            }
        }
    }

    // ---------- WHERE / GROUP BY ----------

    private void emitWhereFilters(int start, int end, int baseDepth, AliasTable aliases,
                                  String ownerView, int branchIndex) {
        int whereIdx = findTop(start, end, baseDepth, "WHERE");
        if (whereIdx < 0) return;
        int wEnd = clauseEnd(whereIdx + 1, end, baseDepth, Set.of("GROUP", "ORDER", "HAVING", "UNION"));
        for (ColRef cr : ExpressionScanner.scanColumnRefs(slice(whereIdx + 1, wEnd))) {
            emitFilterEdge(cr, "where", "", ownerView, branchIndex, aliases);
        }
    }

    private void emitGroupBy(int start, int end, int baseDepth, String ownerView,
                             int branchIndex, UnionBranchMerger.Verdict verdict,
                             List<SelectItem> items, AliasTable aliases) {
        int groupIdx = findTop(start, end, baseDepth, "GROUP");
        if (groupIdx < 0) return;
        int gStart = groupIdx + 2; // 跳过 GROUP BY
        int gEnd = clauseEnd(gStart, end, baseDepth, Set.of("ORDER", "HAVING", "UNION"));
        List<Tok> seg = slice(gStart, gEnd);

        for (List<Tok> g : splitByTopComma(seg, baseDepth)) {
            if (g.isEmpty()) continue;
            Tok first = g.get(0);
            if (first.is(Type.NUMBER)) {
                int n;
                try { n = Integer.parseInt(first.text.split("\\.")[0]); }
                catch (NumberFormatException e) { n = -1; }
                if (n < 1 || n > items.size()) {
                    addReview(Reason.POSITIONAL_GROUP_BY, ownerView, lineCol(first.line, first.column),
                            "GROUP BY 位置引用 " + first.text + " 越界，无法解析为投影列，请人工核对");
                    continue;
                }
                SelectItem target = items.get(n - 1);
                Result tr = ExpressionScanner.scanExpression(target.exprTokens, baseDepth);
                if (tr.category == Category.AGGREGATE || tr.category == Category.WINDOW
                        || tr.category == Category.STAR) {
                    addReview(Reason.POSITIONAL_GROUP_BY, ownerView, lineCol(first.line, first.column),
                            "GROUP BY 位置引用 " + first.text + " 指向聚合/星号投影列，不能作为分组键，请人工核对");
                    continue;
                }
                emitGroupEdge(target, n - 1, baseDepth, ownerView, branchIndex, verdict, aliases);
            } else {
                for (ColRef cr : ExpressionScanner.scanColumnRefs(g)) {
                    SelectItem matched = null;
                    int mIdx = -1;
                    for (int j = 0; j < items.size(); j++) {
                        Result r = ExpressionScanner.scanExpression(items.get(j).exprTokens, baseDepth);
                        if (!r.sourceRefs.isEmpty() && r.sourceRefs.get(0).column.equals(cr.column)) {
                            matched = items.get(j);
                            mIdx = j;
                            break;
                        }
                    }
                    if (matched != null) {
                        emitGroupEdge(matched, mIdx, baseDepth, ownerView, branchIndex, verdict, aliases);
                    } else {
                        // 分组列未直接出现在投影中：仍产出分组依赖边，避免静默跳过
                        AliasTable.Ref ref = aliases.resolve(cr.qualifier, cr.column);
                        emitEdge(ownerView, EdgeKind.AGGREGATE, cr.column, ref.table, cr.column,
                                "", "GROUP_BY", false, "", "", branchIndex,
                                cr.line, cr.columnPos, false, false);
                    }
                }
            }
        }
    }

    private void emitGroupEdge(SelectItem item, int position, int baseDepth, String ownerView,
                               int branchIndex, UnionBranchMerger.Verdict verdict, AliasTable aliases) {
        Result res = ExpressionScanner.scanExpression(item.exprTokens, baseDepth);
        if (res.sourceRefs.isEmpty()) return;
        ColRef cr = res.sourceRefs.get(0);
        AliasTable.Ref ref = aliases.resolve(cr.qualifier, cr.column);
        String outName = UnionBranchMerger.mergedColumnName(verdict, branchIndex, position, item.outputName);
        // 分组键输出名无法确定时退回列名本身，避免产出空列名边（build 会拒绝）
        if (outName.isBlank()) outName = cr.column;
        boolean ambiguous = cr.qualifier == null && aliases.isMultiSource();
        emitEdge(ownerView, EdgeKind.AGGREGATE, outName, ref.table, ref.column,
                "", "GROUP_BY", false, "", "", branchIndex, cr.line, cr.columnPos, false, ambiguous);
    }

    // ---------- 过滤边与统一产边 ----------

    private void emitFilterEdge(ColRef cr, String conditionPosition, String joinType,
                                String ownerView, int branchIndex, AliasTable aliases) {
        AliasTable.Ref ref = aliases.resolve(cr.qualifier, cr.column);
        emitEdge(ownerView, EdgeKind.FILTER, cr.column, ref.table, cr.column,
                "", "", false, conditionPosition, joinType, branchIndex,
                cr.line, cr.columnPos, false, false);
    }

    private void emitEdge(String ownerView, EdgeKind kind, String outputColumn,
                          String sourceTable, String sourceColumn, String expression,
                          String aggregateFunction, boolean distinct, String conditionPosition,
                          String joinType, int branchIndex, int line, int column,
                          boolean unresolved, boolean ambiguous) {
        double confidence = EdgeConfidencePolicy.confidenceOf(kind, 1, joinType, ambiguous);
        ColumnEdge edge = ColumnEdge.builder()
                .viewName(ownerView)
                .outputColumn(outputColumn)
                .sourceTable(sourceTable)
                .sourceColumn(sourceColumn)
                .edgeKind(kind)
                .expression(expression)
                .aggregateFunction(aggregateFunction)
                .distinct(distinct)
                .conditionPosition(conditionPosition)
                .joinType(joinType)
                .branchIndex(branchIndex)
                .line(line)
                .column(column)
                .unresolved(unresolved)
                .ambiguous(ambiguous)
                .confidence(confidence)
                .build();
        edgeMap.putIfAbsent(logicalKey(edge), edge);
    }

    /** 逻辑去重键：忽略行列号与置信度，保证结果同序、可复现。 */
    private static String logicalKey(ColumnEdge e) {
        return String.join("|",
                e.viewName(), e.outputColumn(), e.sourceTable(), e.sourceColumn(),
                e.edgeKind() == null ? "" : e.edgeKind().wireName(),
                e.expression(), e.aggregateFunction(), Boolean.toString(e.distinct()),
                e.conditionPosition(), e.joinType(), Integer.toString(e.branchIndex()),
                Boolean.toString(e.unresolved()), Boolean.toString(e.ambiguous()));
    }

    private void addReview(Reason reason, String source, String position, String hint) {
        String key = source + "|" + position + "|" + reason.wireName();
        if (reviewKeys.add(key)) reviews.add(new ManualReviewItem(source, position, reason, hint));
    }

    // ---------- 通用 helper ----------

    private int findTop(int start, int end, int baseDepth, String word) {
        for (int k = start; k < end && k < tokens.size(); k++) {
            Tok t = tokens.get(k);
            if (t.depth == baseDepth && t.isWord(word)) return k;
        }
        return -1;
    }

    private int clauseEnd(int from, int end, int baseDepth, Set<String> keywords) {
        for (int k = from; k < end && k < tokens.size(); k++) {
            Tok t = tokens.get(k);
            if (t.depth == baseDepth && keywords.contains(t.upper)) return k;
        }
        return end;
    }

    private int matchRparenAt(int lp) {
        int d = tokens.get(lp).depth;
        for (int j = lp + 1; j < tokens.size(); j++) {
            if (tokens.get(j).is(Type.RPAREN) && tokens.get(j).depth == d) return j;
        }
        return tokens.size() - 1;
    }

    private List<Tok> slice(int from, int to) {
        int a = Math.max(0, Math.min(from, tokens.size()));
        int b = Math.max(a, Math.min(to, tokens.size()));
        return new ArrayList<>(tokens.subList(a, b));
    }

    private static List<List<Tok>> splitByTopComma(List<Tok> seg, int baseDepth) {
        List<List<Tok>> out = new ArrayList<>();
        List<Tok> cur = new ArrayList<>();
        for (Tok t : seg) {
            if (t.is(Type.COMMA) && t.depth == baseDepth) {
                if (!cur.isEmpty()) out.add(cur);
                cur = new ArrayList<>();
            } else {
                cur.add(t);
            }
        }
        if (!cur.isEmpty()) out.add(cur);
        return out;
    }

    private static boolean isTableKeyword(String up) { return TABLE_KEYWORDS.contains(up); }

    private static String lineCol(int line, int column) {
        return "第 " + line + " 行第 " + column + " 列";
    }
}
