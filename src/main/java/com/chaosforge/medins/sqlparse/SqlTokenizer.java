package com.chaosforge.medins.sqlparse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 轻量 SQL 词法扫描器：把 SQL 文本切成带行列号与括号深度的 token。
 *
 * <p>本扫描器只服务于列级归因的结构化遍历，不承担完整语法校验（校验由
 * {@code SQLUtils.parseSingleStatement} 完成）。它明确保留字符串字面量、数字、
 * 标识符、运算符的类型区分，使上层不会把常量、函数调用、伪列误判为来源列。
 *
 * <p>每个 token 记录全局括号深度：左括号 token 的深度等于其外层深度，随后深度加一；
 * 右括号先把深度减一，其深度等于外层深度。于是"某个 SELECT 单元所在层"的顶层
 * token 深度等于该层基准深度，内层括号（函数参数、子查询、OVER 窗口、CTE 体）
 * 的深度更大，可借此干净地切分投影项与定位同层关键字。
 */
public final class SqlTokenizer {

    private SqlTokenizer() { }

    /** token 类型。 */
    public enum Type {
        WORD,      // 标识符 / 关键字 / 函数名
        NUMBER,    // 数字字面量
        STRING,    // 单引号字符串字面量（内容不含引号）
        LPAREN, RPAREN, COMMA, DOT, STAR, OP, SEMI, OTHER
    }

    /** 单个 token，携带文本、大写形式、行列号、字符偏移与括号深度。 */
    public static final class Tok {
        public final Type type;
        public final String text;
        public final String upper;
        public final int line;
        public final int column;
        public final int start;
        public final int end;
        public final int depth;

        Tok(Type type, String text, int line, int column, int start, int end, int depth) {
            this.type = type;
            this.text = text;
            this.upper = text == null ? "" : text.toUpperCase(Locale.ROOT);
            this.line = line;
            this.column = column;
            this.start = start;
            this.end = end;
            this.depth = depth;
        }

        public boolean is(Type t) { return type == t; }

        /** 是否为大写等于给定值的标识符 / 关键字 / 函数名。 */
        public boolean isWord(String up) { return type == Type.WORD && upper.equals(up); }

        @Override
        public String toString() { return upper + "@" + line + ":" + column + "(d" + depth + ")"; }
    }

    /** 把 SQL 文本切成 token 列表；注释被剔除但行列号保持与原文一致。 */
    public static List<Tok> tokenize(String sql) {
        List<Tok> out = new ArrayList<>();
        if (sql == null) return out;
        int n = sql.length();
        int line = 1, col = 1, depth = 0;
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            char nx = i + 1 < n ? sql.charAt(i + 1) : '\0';

            // 空白：累计行列号
            if (Character.isWhitespace(c)) {
                if (c == '\n') { line++; col = 1; } else { col++; }
                i++;
                continue;
            }
            // 行注释 --
            if (c == '-' && nx == '-') {
                while (i < n && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') { i++; col++; }
                continue;
            }
            // 块注释 /* ... */
            if (c == '/' && nx == '*') {
                i += 2; col += 2;
                while (i < n) {
                    char d = sql.charAt(i);
                    char d2 = i + 1 < n ? sql.charAt(i + 1) : '\0';
                    if (d == '*' && d2 == '/') { i += 2; col += 2; break; }
                    if (d == '\n') { line++; col = 1; i++; } else { col++; i++; }
                }
                continue;
            }
            // 单引号字符串字面量
            if (c == '\'') {
                int start = i, sl = line, sc = col;
                StringBuilder sb = new StringBuilder();
                i++; col++;
                while (i < n) {
                    char d = sql.charAt(i);
                    if (d == '\'') {
                        if (i + 1 < n && sql.charAt(i + 1) == '\'') { sb.append('\''); i += 2; col += 2; continue; }
                        i++; col++; break;
                    }
                    if (d == '\n') { line++; col = 1; } else { col++; }
                    sb.append(d); i++;
                }
                out.add(new Tok(Type.STRING, sb.toString(), sl, sc, start, i, depth));
                continue;
            }
            // 双引号标识符（Oracle）与反引号标识符（MySQL，方言检测另行定位）
            if (c == '"' || c == '`') {
                char quote = c;
                int start = i, sl = line, sc = col;
                StringBuilder sb = new StringBuilder();
                i++; col++;
                while (i < n && sql.charAt(i) != quote) { sb.append(sql.charAt(i)); i++; col++; }
                if (i < n) { i++; col++; }
                out.add(new Tok(Type.WORD, sb.toString(), sl, sc, start, i, depth));
                continue;
            }
            // 数字（含小数点）
            if (Character.isDigit(c)) {
                int start = i, sl = line, sc = col;
                StringBuilder sb = new StringBuilder();
                while (i < n && (Character.isDigit(sql.charAt(i)) || sql.charAt(i) == '.')) {
                    sb.append(sql.charAt(i)); i++; col++;
                }
                out.add(new Tok(Type.NUMBER, sb.toString(), sl, sc, start, i, depth));
                continue;
            }
            // 标识符 / 关键字 / 函数名
            if (Character.isLetter(c) || c == '_') {
                int start = i, sl = line, sc = col;
                StringBuilder sb = new StringBuilder();
                while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_'
                        || sql.charAt(i) == '$' || sql.charAt(i) == '#')) {
                    sb.append(sql.charAt(i)); i++; col++;
                }
                out.add(new Tok(Type.WORD, sb.toString(), sl, sc, start, i, depth));
                continue;
            }

            // 运算符 / 标点
            int start = i, sl = line, sc = col;
            if (c == '(') {
                out.add(new Tok(Type.LPAREN, "(", sl, sc, start, i + 1, depth));
                depth++; i++; col++;
                continue;
            }
            if (c == ')') {
                depth = Math.max(0, depth - 1);
                out.add(new Tok(Type.RPAREN, ")", sl, sc, start, i + 1, depth));
                i++; col++;
                continue;
            }
            Type t;
            String txt;
            if (c == ',') { t = Type.COMMA; txt = ","; i++; col++; }
            else if (c == '.') { t = Type.DOT; txt = "."; i++; col++; }
            else if (c == '*') { t = Type.STAR; txt = "*"; i++; col++; }
            else if (c == ';') { t = Type.SEMI; txt = ";"; i++; col++; }
            else {
                if ((c == '>' || c == '<' || c == '!' || c == ':')
                        && (nx == '=' || nx == '>' || nx == '<' || nx == ':')) {
                    txt = "" + c + nx; i += 2; col += 2;
                } else if (c == '|' && nx == '|') {
                    txt = "||"; i += 2; col += 2;
                } else {
                    txt = String.valueOf(c); i++; col++;
                }
                t = Type.OP;
            }
            out.add(new Tok(t, txt, sl, sc, start, i, depth));
        }
        return out;
    }
}
