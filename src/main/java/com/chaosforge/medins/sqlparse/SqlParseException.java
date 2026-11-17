package com.chaosforge.medins.sqlparse;

/** 解析失败异常：始终携带行列号与错误码，不吞异常。 */
public class SqlParseException extends Exception {

    private final int line;
    private final int column;
    private final String code;

    public SqlParseException(int line, int column, String code, String message) {
        super("第 " + line + " 行第 " + column + " 列：" + message);
        this.line = line;
        this.column = column;
        this.code = code == null ? "PARSE_ERROR" : code;
    }

    public SqlParseException(int line, int column, String code, String message, Throwable cause) {
        super("第 " + line + " 行第 " + column + " 列：" + message, cause);
        this.line = line;
        this.column = column;
        this.code = code == null ? "PARSE_ERROR" : code;
    }

    public int line() { return line; }
    public int column() { return column; }
    public String code() { return code; }

    public StructuredError toStructuredError() {
        return new StructuredError(line, column, code, getMessage());
    }
}
