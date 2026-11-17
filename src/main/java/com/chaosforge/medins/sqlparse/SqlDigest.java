package com.chaosforge.medins.sqlparse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** SQL 规范化摘要与幂等键：仅空白与注释差异视为同一段 SQL。 */
public final class SqlDigest {

    private SqlDigest() { }

    /** 剔除行注释与块注释、统一关键字大小写、折叠空白；保留字符串字面量内容。 */
    public static String normalize(String sql) {
        if (sql == null) return "";
        StringBuilder out = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (inLineComment) {
                if (c == '\n' || c == '\r') { inLineComment = false; out.append(' '); }
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') { inBlockComment = false; i++; }
                continue;
            }
            if (inSingleQuote) {
                out.append(c);
                if (c == '\'') {
                    if (next == '\'') { out.append(next); i++; }
                    else inSingleQuote = false;
                }
                continue;
            }
            if (c == '-' && next == '-') { inLineComment = true; i++; continue; }
            if (c == '/' && next == '*') { inBlockComment = true; i++; continue; }
            if (c == '\'') { inSingleQuote = true; out.append(c); continue; }
            if (Character.isWhitespace(c)) { out.append(' '); continue; }
            out.append(Character.toUpperCase(c));
        }
        String collapsed = out.toString().replaceAll("\\s+", " ").trim();
        return collapsed.toUpperCase(Locale.ROOT);
    }

    public static String sha256Hex(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest((raw == null ? "" : raw).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("运行环境缺少 SHA-256 实现", e);
        }
    }

    /** 规范化后取 sha256，输出 64 位十六进制。 */
    public static String digest(String sql) {
        return sha256Hex(normalize(sql));
    }

    /** 幂等键 sha256(view_name|sql_digest|changed_field)，与 spec.md §2.4 一致。 */
    public static String idempotencyKey(String viewName, String sqlDigest, String changedField) {
        String joined = nz(viewName) + "|" + nz(sqlDigest) + "|" + nz(changedField);
        return sha256Hex(joined);
    }

    private static String nz(String s) { return s == null ? "" : s.trim().toUpperCase(Locale.ROOT); }
}
