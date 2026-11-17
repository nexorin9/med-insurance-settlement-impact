package com.chaosforge.medins.sqlparse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 别名表：解析阶段收集表别名、列别名、CTE 名与子查询名，列引用先按别名表归一，
 * 未命中再按原名匹配；JOIN 多源场景下的无限定名列由调用方据此判定同名歧义。
 *
 * <p>约定（与契约 §3.1 / Task 9 一致）：
 * <ul>
 *   <li>普通表：{@code registerTable(alias, 真实表名)}，{@code o.col} 归一为 {@code 真实表名.col}；</li>
 *   <li>CTE 引用：把 CTE 名当作"虚拟视图节点"注册为表，{@code c.item_id} 归一为 {@code COST_BASE.item_id}；</li>
 *   <li>FROM 子查询：{@code registerSubquery(alias, 输出列->底层来源)}，{@code s.bill_no} 归并回底层字段键。</li>
 * </ul>
 */
public final class AliasTable {

    /** 归一后的来源：表（或虚拟视图）名 + 列名，均为大写。 */
    public static final class Ref {
        public final String table;
        public final String column;

        public Ref(String table, String column) {
            this.table = table == null ? "" : table.toUpperCase(Locale.ROOT);
            this.column = column == null ? "" : column.toUpperCase(Locale.ROOT);
        }
    }

    private final Map<String, String> tableAliases = new LinkedHashMap<>();      // 别名大写 -> 真实表/虚拟视图名大写
    private final Map<String, Map<String, Ref>> subqueryColumns = new LinkedHashMap<>(); // 子查询别名 -> 输出列 -> 底层来源
    private final Set<String> realTables = new LinkedHashSet<>();                // 出现过的表/虚拟视图名（去重）
    private final Set<String> cteNames = new LinkedHashSet<>();                  // 注册过的 CTE 名（大写）
    private final Set<String> unresolvedSources = new LinkedHashSet<>();        // 无法归并的子查询别名
    private int sourceCount;                                                     // FROM/JOIN 表源数量

    /** 注册普通表或 CTE 引用。alias 为空时以表名自身作为键。 */
    public void registerTable(String alias, String table) {
        String key = (alias == null || alias.isBlank() ? table : alias).toUpperCase(Locale.ROOT);
        String tbl = table == null ? "" : table.toUpperCase(Locale.ROOT);
        tableAliases.put(key, tbl);
        realTables.add(tbl);
        sourceCount++;
    }

    /** 登记 CTE 名（虚拟视图节点），供上层断言与外层消费边使用。 */
    public void registerCte(String name) {
        cteNames.add(name == null ? "" : name.toUpperCase(Locale.ROOT));
    }

    /** 注册 FROM 子查询：把其输出列名映射到底层真实来源，实现别名归并。 */
    public void registerSubquery(String alias, Map<String, Ref> columns) {
        Map<String, Ref> normalized = new LinkedHashMap<>();
        if (columns != null) {
            for (Map.Entry<String, Ref> e : columns.entrySet()) {
                normalized.put(e.getKey().toUpperCase(Locale.ROOT), e.getValue());
            }
        }
        subqueryColumns.put(alias.toUpperCase(Locale.ROOT), normalized);
        sourceCount++;
    }

    /** 登记无法安全归并的子查询别名：外层引用它时只产不可解析标记边，不冒充具体来源列。 */
    public void registerUnresolvedSubquery(String alias) {
        if (alias != null && !alias.isBlank()) {
            unresolvedSources.add(alias.toUpperCase(Locale.ROOT));
            sourceCount++;
        }
    }

    /** FROM/JOIN 是否含两个及以上表源（无限定名列在此场景下可能同名歧义）。 */
    public boolean isMultiSource() { return sourceCount >= 2; }

    public Set<String> cteNames() { return Set.copyOf(cteNames); }

    public int sourceCount() { return sourceCount; }

    /**
     * 解析列引用：qualifier 为表别名 / CTE 别名 / 子查询别名，可空。
     * 返回归一后的 (表, 列)。无限定名在多源场景下取首张表兜底，歧义标记交由调用方判断。
     */
    public Ref resolve(String qualifier, String column) {
        String col = column == null ? "" : column.toUpperCase(Locale.ROOT);
        if (qualifier != null && !qualifier.isBlank()) {
            String q = qualifier.toUpperCase(Locale.ROOT);
            if (unresolvedSources.contains(q)) return new Ref("", ""); // 来源无法判定
            Map<String, Ref> sub = subqueryColumns.get(q);
            if (sub != null) {
                Ref r = sub.get(col);
                return r != null ? r : new Ref(q, col);
            }
            String tbl = tableAliases.get(q);
            if (tbl != null) return new Ref(tbl, col);
            return new Ref(q, col); // 未知别名：按原名，不猜测
        }
        // 无限定名：单表直接归到该表；多源取首表兜底（调用方按需标歧义）
        String first = realTables.isEmpty() ? "" : realTables.iterator().next();
        return new Ref(first, col);
    }

    /** 供诊断输出：已注册的表别名键。 */
    public List<String> aliasKeys() { return new ArrayList<>(tableAliases.keySet()); }
}
