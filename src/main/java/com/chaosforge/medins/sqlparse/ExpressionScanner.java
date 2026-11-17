package com.chaosforge.medins.sqlparse;

import com.chaosforge.medins.sqlparse.SqlTokenizer.Tok;
import com.chaosforge.medins.sqlparse.SqlTokenizer.Type;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 表达式扫描器：把单个投影项（已去掉输出别名）归入 DIRECT / DERIVED / AGGREGATE /
 * WINDOW / STAR 五类，抽取其引用的来源列、聚合函数名、distinct、窗口分区列与表达式摘要。
 *
 * <p>窗口 ORDER BY 排序列被有意排除在任何来源列集合之外 —— 由 ColumnEdgeVisitor
 * 保证它们既不会进入 AGGREGATE 结果边，也不会变成 FILTER 边（契约 §4：窗口排序列不得误报为来源列）。
 *
 * <p>列引用扫描严格跳过字符串、数字、保留字/函数名/伪列，保证常量、函数调用、伪列不被误判为列。
 */
public final class ExpressionScanner {

    private ExpressionScanner() { }

    /** 投影项类别。 */
    public enum Category { DIRECT, DERIVED, AGGREGATE, WINDOW, STAR }

    /** 单个列引用（qualifier 为表/CTE/子查询别名，可空）。 */
    public static final class ColRef {
        public final String qualifier; // 大写，可空
        public final String column;    // 大写
        public final int line;
        public final int columnPos;

        ColRef(String qualifier, String column, int line, int columnPos) {
            this.qualifier = qualifier == null ? null : qualifier.toUpperCase(Locale.ROOT);
            this.column = column.toUpperCase(Locale.ROOT);
            this.line = line;
            this.columnPos = columnPos;
        }
    }

    /** 单个投影项的扫描结果。 */
    public static final class Result {
        public Category category = Category.DIRECT;
        public String functionName = "";                     // 聚合 / 窗口函数名（大写）
        public boolean distinct;
        public String expression = "";                       // DERIVED / AGGREGATE 摘要
        public List<ColRef> sourceRefs = new ArrayList<>();   // 结果边的来源列候选
        public List<ColRef> partitionRefs = new ArrayList<>();// 窗口 PARTITION BY 列（产 FILTER 边）
    }

    /** 关键字 / 函数名 / 伪列保留字（大写）。 */
    private static final Set<String> RESERVED = new HashSet<>(Arrays.asList(
            "SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "NULL", "IS", "IN", "LIKE",
            "BETWEEN", "EXISTS", "AS", "CASE", "WHEN", "THEN", "ELSE", "END", "DISTINCT",
            "GROUP", "BY", "ORDER", "HAVING", "UNION", "ALL", "JOIN", "INNER", "LEFT",
            "RIGHT", "FULL", "OUTER", "CROSS", "ON", "WITH", "OVER", "PARTITION", "ROWS",
            "RANGE", "ASC", "DESC", "DATE", "TIMESTAMP", "INTERVAL", "CAST", "DECODE",
            "NVL", "COALESCE", "UPPER", "LOWER", "SUBSTR", "TRIM", "LENGTH", "TO_CHAR",
            "TO_DATE", "TO_NUMBER", "TO_TIMESTAMP", "SUM", "COUNT", "MAX", "MIN", "AVG",
            "ROW_NUMBER", "RANK", "DENSE_RANK", "FIRST_VALUE", "LAST_VALUE", "LAG", "LEAD",
            "TRUE", "FALSE", "DUAL", "SYSDATE", "SYSTIMESTAMP", "ROWNUM", "LEVEL",
            "CURRENT_DATE", "IF", "IFNULL", "ISNULL", "MOD", "ROUND", "TRUNC", "ABS",
            "CEIL", "FLOOR", "EXTRACT", "NULLIF", "YEAR", "MONTH", "DAY", "ADD_MONTHS",
            "CONCAT", "NVL2", "PRIOR", "CONNECT", "START", "VALUES", "INSERT", "UPDATE",
            "DELETE", "INTO", "LIMIT", "OFFSET", "RETURNING", "ILIKE", "CONFLICT"));

    private static final Set<String> AGGREGATE_FUNCTIONS =
            Set.of("SUM", "COUNT", "MAX", "MIN", "AVG");

    public static boolean isReserved(String up) {
        return up != null && RESERVED.contains(up.toUpperCase(Locale.ROOT));
    }

    /**
     * 扫描任意表达式段中的列引用。跳过：字符串 / 数字、保留字（含函数名与伪列）、
     * 紧跟左括号的函数名、{@code WORD.DOT.WORD} 的尾段、以及 {@code t.*} 形式。
     */
    public static List<ColRef> scanColumnRefs(List<Tok> tokens) {
        List<ColRef> refs = new ArrayList<>();
        int n = tokens.size();
        for (int k = 0; k < n; k++) {
            Tok t = tokens.get(k);
            if (!t.is(Type.WORD)) continue;
            if (isReserved(t.upper)) continue;
            if (k > 0 && tokens.get(k - 1).is(Type.DOT)) continue; // 限定名的尾段
            if (k + 1 < n && tokens.get(k + 1).is(Type.LPAREN)) continue; // 函数名
            if (k + 1 < n && tokens.get(k + 1).is(Type.DOT)) {
                // qualifier . column；t.* 不产出列引用
                if (k + 2 < n && tokens.get(k + 2).is(Type.WORD) && !isReserved(tokens.get(k + 2).upper)) {
                    refs.add(new ColRef(t.upper, tokens.get(k + 2).upper, t.line, t.column));
                }
                continue;
            }
            refs.add(new ColRef(null, t.upper, t.line, t.column)); // 无限定名列
        }
        return refs;
    }

    /** 扫描一个投影项并分类。baseDepth 为该 SELECT 单元顶层 token 的括号深度。 */
    public static Result scanExpression(List<Tok> tokens, int baseDepth) {
        Result r = new Result();
        if (tokens.isEmpty()) return r;

        // SELECT * 与 t.*：不猜测展开
        if (tokens.size() == 1 && tokens.get(0).is(Type.STAR)) {
            r.category = Category.STAR;
            return r;
        }
        if (tokens.size() == 3 && tokens.get(0).is(Type.WORD)
                && tokens.get(1).is(Type.DOT) && tokens.get(2).is(Type.STAR)) {
            r.category = Category.STAR;
            return r;
        }

        // 窗口函数：顶层存在 OVER
        int overIdx = -1;
        for (int k = 0; k < tokens.size(); k++) {
            if (tokens.get(k).isWord("OVER") && tokens.get(k).depth == baseDepth) { overIdx = k; break; }
        }
        if (overIdx >= 0) {
            scanWindow(tokens, overIdx, baseDepth, r);
            return r;
        }

        // 聚合函数：SUM / COUNT / MAX / MIN / AVG
        FunCall agg = firstAggregateFunction(tokens, baseDepth);
        if (agg != null) {
            r.category = Category.AGGREGATE;
            r.functionName = agg.name;
            r.distinct = hasKeyword(agg.args, "DISTINCT");
            r.sourceRefs = scanColumnRefs(agg.args);
            r.expression = render(tokens);
            return r;
        }

        // 纯列引用 vs 派生表达式
        List<Tok> stripped = stripOuterParens(tokens);
        if (isPureColumnRef(stripped)) {
            r.category = Category.DIRECT;
            r.sourceRefs = scanColumnRefs(stripped);
        } else {
            r.category = Category.DERIVED;
            r.sourceRefs = scanColumnRefs(tokens);
            r.expression = render(tokens);
        }
        return r;
    }

    /** 渲染表达式摘要（供 DERIVED / AGGREGATE 边的 expression 元数据）。 */
    public static String render(List<Tok> tokens) {
        StringBuilder sb = new StringBuilder();
        boolean prevWordLike = false;
        for (Tok t : tokens) {
            boolean wordLike = t.is(Type.WORD) || t.is(Type.NUMBER) || t.is(Type.STRING);
            if (sb.length() > 0 && wordLike && prevWordLike) sb.append(' ');
            sb.append(t.text);
            prevWordLike = wordLike;
        }
        return sb.toString();
    }

    // ---------- 窗口函数 ----------

    private static void scanWindow(List<Tok> tokens, int overIdx, int baseDepth, Result r) {
        r.category = Category.WINDOW;
        r.expression = render(tokens);

        // OVER 之前：窗口函数名与参数列
        FunCall fun = firstTopFunction(tokens.subList(0, overIdx), baseDepth);
        List<ColRef> paramRefs = new ArrayList<>();
        if (fun != null) {
            r.functionName = fun.name;
            r.distinct = hasKeyword(fun.args, "DISTINCT");
            paramRefs = scanColumnRefs(fun.args);
        }

        // OVER 之后：窗口规格 ( PARTITION BY ... ORDER BY ... )
        List<Tok> spec = windowSpec(tokens, overIdx, baseDepth);
        int partitionIdx = indexOfWord(spec, "PARTITION");
        int orderIdx = indexOfWord(spec, "ORDER");
        if (partitionIdx >= 0) {
            int from = partitionIdx + 2; // 跳过 PARTITION BY
            int to = orderIdx > partitionIdx ? orderIdx : spec.size();
            r.partitionRefs = scanColumnRefs(safeSub(spec, from, to));
        }
        // ORDER BY 排序列有意不加入任何来源集合（契约 §4 不得误报）

        r.sourceRefs = paramRefs.isEmpty() ? new ArrayList<>(r.partitionRefs) : paramRefs;
    }

    /** 取 OVER 之后第一个顶层括号的内容。 */
    private static List<Tok> windowSpec(List<Tok> tokens, int overIdx, int baseDepth) {
        for (int k = overIdx + 1; k < tokens.size(); k++) {
            Tok t = tokens.get(k);
            if (t.is(Type.LPAREN) && t.depth == baseDepth) {
                int rp = matchRparen(tokens, k);
                return new ArrayList<>(tokens.subList(k + 1, rp));
            }
        }
        return List.of();
    }

    // ---------- 通用 helper ----------

    private static final class FunCall {
        final String name;
        final List<Tok> args;
        FunCall(String name, List<Tok> args) { this.name = name; this.args = args; }
    }

    private static FunCall firstTopFunction(List<Tok> seg, int baseDepth) {
        for (int k = 0; k < seg.size(); k++) {
            Tok t = seg.get(k);
            if (t.is(Type.WORD) && t.depth == baseDepth
                    && k + 1 < seg.size() && seg.get(k + 1).is(Type.LPAREN)
                    && seg.get(k + 1).depth == baseDepth) {
                int lp = k + 1;
                int rp = matchRparen(seg, lp);
                return new FunCall(t.upper, new ArrayList<>(seg.subList(lp + 1, rp)));
            }
        }
        return null;
    }

    private static FunCall firstAggregateFunction(List<Tok> seg, int baseDepth) {
        for (int k = 0; k < seg.size(); k++) {
            Tok t = seg.get(k);
            if (t.is(Type.WORD) && t.depth == baseDepth && AGGREGATE_FUNCTIONS.contains(t.upper)
                    && k + 1 < seg.size() && seg.get(k + 1).is(Type.LPAREN)
                    && seg.get(k + 1).depth == baseDepth) {
                int lp = k + 1;
                int rp = matchRparen(seg, lp);
                return new FunCall(t.upper, new ArrayList<>(seg.subList(lp + 1, rp)));
            }
        }
        return null;
    }

    /** 在 seg 中找与 lpIdx 处左括号匹配的右括号索引（按括号深度）。 */
    private static int matchRparen(List<Tok> seg, int lpIdx) {
        int d = seg.get(lpIdx).depth;
        for (int j = lpIdx + 1; j < seg.size(); j++) {
            if (seg.get(j).is(Type.RPAREN) && seg.get(j).depth == d) return j;
        }
        return seg.size();
    }

    private static boolean hasKeyword(List<Tok> seg, String up) {
        for (Tok t : seg) if (t.isWord(up)) return true;
        return false;
    }

    private static int indexOfWord(List<Tok> seg, String up) {
        for (int k = 0; k < seg.size(); k++) if (seg.get(k).isWord(up)) return k;
        return -1;
    }

    private static List<Tok> safeSub(List<Tok> seg, int from, int to) {
        int a = Math.max(0, Math.min(from, seg.size()));
        int b = Math.max(a, Math.min(to, seg.size()));
        return new ArrayList<>(seg.subList(a, b));
    }

    private static List<Tok> stripOuterParens(List<Tok> tokens) {
        List<Tok> cur = new ArrayList<>(tokens);
        while (cur.size() >= 2 && cur.get(0).is(Type.LPAREN)
                && cur.get(cur.size() - 1).is(Type.RPAREN)
                && matchRparen(cur, 0) == cur.size() - 1) {
            cur = new ArrayList<>(cur.subList(1, cur.size() - 1));
        }
        return cur;
    }

    private static boolean isPureColumnRef(List<Tok> t) {
        if (t.size() == 1 && t.get(0).is(Type.WORD) && !isReserved(t.get(0).upper)) return true;
        return t.size() == 3 && t.get(0).is(Type.WORD) && t.get(1).is(Type.DOT)
                && t.get(2).is(Type.WORD) && !isReserved(t.get(2).upper);
    }
}
