package com.chaosforge.medins.sqlparse;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 待人工核对条目 —— 「讲不清就登记」，不允许静默跳过。 */
public final class ManualReviewItem {

    /** 不可解析原因枚举，供 UI 分组与回归断言使用。 */
    public enum Reason {
        SELECT_STAR("select_star", "SELECT * 依赖表结构元数据，首版不展开"),
        UNION_BRANCH_CONFLICT("union_branch_conflict", "UNION 分支列数或类型不一致，不能安全合并"),
        SUBQUERY_UNRESOLVED("subquery_unresolved", "子查询无法安全归并"),
        AMBIGUOUS_COLUMN("ambiguous_column", "同名列无限定名，来源存在歧义"),
        DIALECT_UNSUPPORTED("dialect_unsupported", "首版仅支持 Oracle 视图方言"),
        POSITIONAL_GROUP_BY("positional_group_by", "GROUP BY 位置引用无法判定"),
        PARSE_FAILURE("parse_failure", "SQL 解析失败"),
        OTHER("other", "其他需人工核对的原因");

        private final String wire;
        private final String zh;

        Reason(String wire, String zh) { this.wire = wire; this.zh = zh; }
        public String wireName() { return wire; }
        public String labelZh() { return zh; }

        public static Reason fromWireName(String raw) {
            for (Reason r : values()) if (r.wire.equalsIgnoreCase(raw)) return r;
            return OTHER;
        }
    }

    private final String source;
    private final String position;
    private final Reason reason;
    private final String hint;

    public ManualReviewItem(String source, String position, Reason reason, String hint) {
        this.source = source == null ? "" : source;
        this.position = position == null ? "" : position;
        this.reason = reason == null ? Reason.OTHER : reason;
        this.hint = hint == null ? "" : hint;
    }

    public String source() { return source; }
    public String position() { return position; }
    public Reason reason() { return reason; }
    public String hint() { return hint; }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", source);
        m.put("position", position);
        m.put("reason", reason.wireName());
        m.put("reason_zh", reason.labelZh());
        m.put("hint", hint);
        return m;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ManualReviewItem that)) return false;
        return Objects.equals(source, that.source) && Objects.equals(position, that.position)
                && reason == that.reason && Objects.equals(hint, that.hint);
    }

    @Override
    public int hashCode() { return Objects.hash(source, position, reason, hint); }

    @Override
    public String toString() { return source + "@" + position + " " + reason.wireName() + " " + hint; }
}
