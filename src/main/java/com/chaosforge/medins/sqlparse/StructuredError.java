package com.chaosforge.medins.sqlparse;

import java.util.LinkedHashMap;
import java.util.Map;

/** 带行列号的结构化解析错误 —— 不吞异常，也不把整段栈打到用户界面。 */
public final class StructuredError {

    private final int line;
    private final int column;
    private final String code;
    private final String message;

    public StructuredError(int line, int column, String code, String message) {
        this.line = Math.max(0, line);
        this.column = Math.max(0, column);
        this.code = code == null ? "PARSE_ERROR" : code;
        this.message = message == null ? "" : message;
    }

    public int line() { return line; }
    public int column() { return column; }
    public String code() { return code; }
    public String message() { return message; }

    /** 形如「第 3 行第 7 列：xxx」，供 CLI 与 UI 直接显示。 */
    public String locatedMessage() {
        return "第 " + line + " 行第 " + column + " 列：" + message;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("line", line);
        m.put("column", column);
        m.put("code", code);
        m.put("message", message);
        return m;
    }
}
