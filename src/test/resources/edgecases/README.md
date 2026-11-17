# 五类边界与异常样例（降级行为固化）

本目录固化 sqlparse 模块五类已知短板的**期望降级行为**，供 `SqlParseEdgeCaseTest` 逐条断言。
总原则：**讲不清就登记，绝不静默跳过，绝不产出「看似完整」的错边**。
五条样例均可复现、无网络/数据库依赖，`parse` 对前四类返回「成功但降级」结果，对第五类返回
`failure`（带行列号）；四类的降级段同时计入 `ParseReport.degradedSegments()`。

| 文件 | 类别 | 期望 reason（wireName） | 期望降级行为 | 提示语（hint 要点） |
|---|---|---|---|---|
| `select_star.sql` | 星号投影 | `select_star` | 1 条 `output_column="*"`、`unresolved=true` 标记边；1 条待核对条目；条目数=未解析列数=1；不展开具体列名 | SELECT * 依赖表结构元数据，首版不展开，请人工确认输出列及其来源 |
| `union_branch_conflict.sql` | UNION 分支列数不一致 | `union_branch_conflict` | 两分支边分别保留（branchIndex=0/1）登记冲突条目；不静默合并、不丢弃任一分支 | 分支列数不一致不能安全合并，各分支边已分别保留，请人工核对列对齐关系 |
| `subquery_unresolved.sql` | 子查询无法归并 | `subquery_unresolved` | 子查询 `SELECT *` 输出列无法确定 → 登记条目；外层引用只产 unresolved 标记边，不冒充具体来源列 | FROM 子查询输出列无法确定来源或缺少别名，不能安全归并 |
| `positional_group_by.sql` | 位置引用歧义 | `positional_group_by` | `GROUP BY 3` 指向聚合结果列，登记条目；不把字面序号 "3" 当列名、不把聚合列静默当分组键 | GROUP BY 位置引用 3 指向聚合/星号投影列，不能作为分组键 |
| `dialect_unsupported.sql` | 方言不支持 | `dialect_unsupported` | MySQL 反引号 + LIMIT 显式判「首版不支持」；`parse` 返回 `failure` + 带行列号 `StructuredError` + 待核对条目 | 首版仅支持 Oracle 视图方言，检测到 MySQL 特有语法 |

## 逐条断言要点（SqlParseEdgeCaseTest）

1. 五条样例解析后 `reviewItems()` 均 ≥ 1，且对应 `reason.wireName()` 命中上表；
2. 没有任何一条被静默跳过：星号段有 unresolved 标记边、UNION 冲突段两分支边齐全、
   子查询段有 unresolved 标记边、位置引用段有条目、方言段有显式错误；
3. 不产「看似完整」的错边：星号段不得输出具体列名冒充已解析、UNION 冲突段不得只取一个分支、
   子查询无法归并段不得把子查询别名当成已解析的真实来源表；
4. 方言段的错误含「行」「列」，`StructuredError.line()/column()` 落在特征语句附近；
5. 四类降级样例（星号/UNION/子查询/位置引用）`report().degradedSegments() >= 1`，
   降级原因分布 `degradationReasons()` 命中对应 reason。

## 与 12 段固定样例的关系

`select_star.sql`、`union_branch_conflict.sql` 与 `src/main/resources/samples/views/` 下的
`v_settlement_star.sql`、`v_fee_detail_union_bad.sql` 表达同类短板，但本目录样例是**独立于固定样例**
的复现输入，用于锁定降级行为；固定样例参与 `golden/edges/` 的 12/12 回归比对，二者互不替代。
