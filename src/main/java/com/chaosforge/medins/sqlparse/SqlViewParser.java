package com.chaosforge.medins.sqlparse;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.util.JdbcConstants;
import com.chaosforge.medins.sqlparse.ManualReviewItem.Reason;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 视图 SQL 解析器入口（签名严格按契约 §3.1）。
 *
 * <p>流程：{@link SqlDialect#detect} 做 Oracle 白名单检测（MySQL/PG 特征显式报「首版不支持」
 * 并带行列号）→ Druid {@code SQLUtils.parseSingleStatement(sql, JdbcConstants.ORACLE)} 做真实
 * 语法校验并捕获异常转带位置的结构化错误 → {@link ColumnEdgeVisitor} 做四类列级归因 →
 * 组装 {@link ParseResult} / {@link ParseReport}（含降级统计）。
 *
 * <p>{@link #parse} 对 UI 友好：失败返回 failure 结果而不抛异常；{@link #parseStrict} 抛
 * {@link SqlParseException}，供需要显式失败语义的调用方使用。异常一律不吞、不静默跳过。
 */
public final class SqlViewParser {

    private static final Pattern POS_PATTERN = Pattern.compile("pos\\s+(\\d+)");
    private static final Pattern LINE_PATTERN = Pattern.compile("line\\s+(\\d+)");
    private static final Pattern COLUMN_PATTERN = Pattern.compile("column\\s+(\\d+)");

    /** 解析失败返回 failure 结果，不把异常抛给 UI。 */
    public ParseResult parse(String viewName, String sql) {
        try {
            return parseStrict(viewName, sql);
        } catch (SqlParseException e) {
            StructuredError error = e.toStructuredError();
            String position = "第 " + e.line() + " 行第 " + e.column() + " 列";
            ManualReviewItem review = new ManualReviewItem(
                    viewName == null ? "" : viewName,
                    position,
                    reasonFor(e.code()),
                    "SQL 解析失败，请按行列号核对语法或方言，人工确认列来源后再纳入影响分析");
            return ParseResult.failure(viewName, SqlDialect.detect(sql),
                    SqlDigest.digest(sql), error, review);
        }
    }

    /** 严格解析：方言不支持或语法错误时抛带行列号的 SqlParseException。 */
    public ParseResult parseStrict(String viewName, String sql) throws SqlParseException {
        if (sql == null || sql.isBlank()) {
            throw new SqlParseException(1, 1, "PARSE_ERROR", "SQL 文本为空，无法解析");
        }

        // 1) Oracle 方言白名单
        SqlDialect dialect = SqlDialect.detect(sql);
        if (dialect != SqlDialect.ORACLE) {
            int[] lc = locateFeature(sql, dialect);
            throw new SqlParseException(lc[0], lc[1], "DIALECT_UNSUPPORTED",
                    "首版仅支持 Oracle 视图方言，检测到 " + dialect.labelZh()
                            + " 特有语法（反引号 / LIMIT / ON DUPLICATE KEY / :: 等），不做猜测解析");
        }

        // 2) Druid 真实解析校验，异常转成带行列号的结构化错误
        SQLStatement statement;
        try {
            statement = SQLUtils.parseSingleStatement(sql, JdbcConstants.ORACLE);
        } catch (RuntimeException ex) {
            int[] lc = locateError(sql, ex);
            throw new SqlParseException(lc[0], lc[1], "PARSE_ERROR",
                    "SQL 解析失败：" + brief(ex.getMessage()), ex);
        }
        // 括号平衡兜底：某些残缺 SQL 可能未触发 Druid 异常，仍按行列号显式报错
        int[] unbalanced = findUnbalancedParen(sql);
        if (unbalanced != null) {
            throw new SqlParseException(unbalanced[0], unbalanced[1], "PARSE_ERROR",
                    "SQL 括号不匹配，无法解析");
        }

        // 3) 列级归因
        ColumnEdgeVisitor visitor = new ColumnEdgeVisitor(viewName, sql, statement);
        visitor.analyze();
        List<ColumnEdge> edges = visitor.edges();
        List<ManualReviewItem> reviews = visitor.reviewItems();

        // 4) 组装成功结果与运行统计（讲不清就登记的段同时计入降级）
        ParseReport report = new ParseReport();
        report.addSuccess(edges.size());
        report.countEdges(edges);
        report.addReviewItems(reviews);
        for (ManualReviewItem item : reviews) {
            if (isDegraded(item.reason())) report.addDegraded(item.reason().wireName());
        }
        return new ParseResult(viewName, SqlDialect.ORACLE, SqlDigest.digest(sql),
                edges, reviews, List.of(), report, true);
    }

    /** 保持输入顺序地解析多段视图。 */
    public List<ParseResult> parseAll(Map<String, String> viewSqls) {
        List<ParseResult> out = new ArrayList<>();
        if (viewSqls == null) return out;
        for (Map.Entry<String, String> e : viewSqls.entrySet()) {
            out.add(parse(e.getKey(), e.getValue()));
        }
        return out;
    }

    /** 汇总多段解析结果的运行统计。 */
    public ParseReport aggregate(List<ParseResult> results) {
        ParseReport report = new ParseReport();
        if (results == null) return report;
        for (ParseResult r : results) {
            if (r != null) report.merge(r.report());
        }
        return report;
    }

    /** 汇总所有待人工核对条目。 */
    public List<ManualReviewItem> pendingReview(List<ParseResult> results) {
        List<ManualReviewItem> out = new ArrayList<>();
        if (results == null) return out;
        for (ParseResult r : results) {
            if (r != null) out.addAll(r.reviewItems());
        }
        return out;
    }

    // ---------- 错误定位 ----------

    private static Reason reasonFor(String code) {
        return "DIALECT_UNSUPPORTED".equals(code) ? Reason.DIALECT_UNSUPPORTED : Reason.PARSE_FAILURE;
    }

    private static boolean isDegraded(Reason reason) {
        return switch (reason) {
            case SELECT_STAR, UNION_BRANCH_CONFLICT, SUBQUERY_UNRESOLVED,
                 AMBIGUOUS_COLUMN, POSITIONAL_GROUP_BY -> true;
            default -> false;
        };
    }

    /** 从 Druid 异常信息提取 pos / line / column，换算成 1-based 行列号。 */
    private static int[] locateError(String sql, RuntimeException ex) {
        // 括号不匹配时自算的位置最精确，优先采用，保证行列号落在错误语句附近
        int[] unbalanced = findUnbalancedParen(sql);
        if (unbalanced != null) return unbalanced;
        String msg = ex == null || ex.getMessage() == null ? "" : ex.getMessage();
        Matcher pos = POS_PATTERN.matcher(msg);
        if (pos.find()) {
            try {
                int offset = Integer.parseInt(pos.group(1));
                return lineColumnOf(sql, Math.min(offset, sql.length()));
            } catch (NumberFormatException ignored) { /* 落到兜底 */ }
        }
        Matcher lineM = LINE_PATTERN.matcher(msg);
        Matcher colM = COLUMN_PATTERN.matcher(msg);
        if (lineM.find()) {
            int line = parseIntOr(lineM.group(1), 1);
            int column = colM.find() ? parseIntOr(colM.group(1), 1) : 1;
            return new int[]{Math.max(1, line), Math.max(1, column)};
        }
        return lastNonBlankPosition(sql);
    }

    /** 找 MySQL/PG 特征子串所在行列号。 */
    private static int[] locateFeature(String sql, SqlDialect dialect) {
        String lower = sql.toLowerCase(Locale.ROOT);
        String[] features;
        if (dialect == SqlDialect.MYSQL) {
            features = new String[]{"`", "limit ", "on duplicate key", "ifnull(", "group_concat("};
        } else {
            features = new String[]{"::", "ilike ", "on conflict", "returning ", "jsonb_"};
        }
        int best = -1;
        for (String f : features) {
            int idx = lower.indexOf(f);
            if (idx >= 0 && (best < 0 || idx < best)) best = idx;
        }
        if (best < 0) best = 0;
        return lineColumnOf(sql, best);
    }

    /** 括号平衡检测：返回首个不匹配括号的 [行,列]，全部匹配则返回 null。 */
    private static int[] findUnbalancedParen(String sql) {
        int[] stackLine = new int[256];
        int[] stackCol = new int[256];
        int top = 0;
        int line = 1, col = 1;
        boolean inString = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char nx = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (inString) {
                if (c == '\'') {
                    if (nx == '\'') { i++; col++; } else inString = false;
                }
                if (c == '\n') { line++; col = 1; } else col++;
                continue;
            }
            if (c == '-' && nx == '-') {
                while (i < sql.length() && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') { i++; col++; }
                i--;
                continue;
            }
            if (c == '\'') { inString = true; col++; continue; }
            if (c == '(') {
                if (top < stackLine.length) { stackLine[top] = line; stackCol[top] = col; top++; }
                col++;
            } else if (c == ')') {
                if (top == 0) return new int[]{line, col};
                top--;
                col++;
            } else if (c == '\n') { line++; col = 1; }
            else col++;
        }
        if (top > 0) return new int[]{stackLine[top - 1], stackCol[top - 1]};
        return null;
    }

    private static int[] lineColumnOf(String sql, int index) {
        int line = 1, lastNl = -1;
        int safe = Math.max(0, Math.min(index, sql.length()));
        for (int i = 0; i < safe; i++) {
            if (sql.charAt(i) == '\n') { line++; lastNl = i; }
        }
        int column = safe - lastNl;
        return new int[]{line, Math.max(1, column)};
    }

    private static int[] lastNonBlankPosition(String sql) {
        int line = 1, col = 1;
        int lastLine = 1, lastCol = 1;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (!Character.isWhitespace(c)) { lastLine = line; lastCol = col; }
            if (c == '\n') { line++; col = 1; } else col++;
        }
        return new int[]{Math.max(1, lastLine), Math.max(1, lastCol)};
    }

    private static int parseIntOr(String raw, int fallback) {
        try { return Integer.parseInt(raw); } catch (NumberFormatException e) { return fallback; }
    }

    /** 只保留异常首行并限长，避免把整段栈打到用户界面。 */
    private static String brief(String msg) {
        if (msg == null || msg.isBlank()) return "解析异常";
        String first = msg.split("\\R", 2)[0].trim();
        return first.length() > 160 ? first.substring(0, 160) + "…" : first;
    }
}
