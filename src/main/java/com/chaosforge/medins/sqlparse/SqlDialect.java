package com.chaosforge.medins.sqlparse;

/** SQL 方言白名单：首版只启用 Oracle，其他方言显式报「首版不支持」。 */
public enum SqlDialect {
    ORACLE("oracle", "Oracle", true),
    MYSQL("mysql", "MySQL", false),
    POSTGRESQL("postgresql", "PostgreSQL", false),
    UNSUPPORTED("unsupported", "未知方言", false);

    private final String wire;
    private final String zh;
    private final boolean supported;

    SqlDialect(String wire, String zh, boolean supported) {
        this.wire = wire;
        this.zh = zh;
        this.supported = supported;
    }

    public String wireName() { return wire; }
    public String labelZh() { return zh; }
    public boolean supported() { return supported; }

    /** 仅凭文本特征识别其它方言，用于给出明确的「首版不支持」错误而非猜测解析。 */
    public static SqlDialect detect(String sql) {
        if (sql == null) return ORACLE;
        String s = sql.toLowerCase();
        if (s.contains("`") || s.contains("limit ") || s.contains("on duplicate key")
                || s.contains("ifnull(") || s.contains("group_concat(")) return MYSQL;
        if (s.contains("::") || s.contains("ilike ") || s.contains("jsonb_")
                || s.contains("on conflict") || s.contains("returning ")) return POSTGRESQL;
        return ORACLE;
    }
}
