# golden/edges 逐段人工复核结论

本目录 12 份快照是「医保结算清单取数影响分析」sqlparse 模块的回归基准。
所有 `expected` 项均**先由人阅读 `src/main/resources/samples/views/<view>.sql` 原文，逐列判定「输出列从哪张表哪一列、以何种方式读出」后手工誊写**，
随后才与解析器实现对照；**没有把解析器某次运行的输出直接当作期望值**（自证式回归等于没测）。

比对口径（`SqlParseGoldenTest`）：以 `expected` 中的四元组
`output_column / source_table / source_column / edge_kind` 为「必须出现的期望项」，
采用「期望项 ⊆ 实际边集合」逐条核对并列出差异；`required_review_reasons` 为本段必须出现的
待人工核对原因（`ManualReviewItem.Reason.wireName()`）。表名与列名统一大写，输出列名保留视图别名原样。
过滤边不直接对应某个投影输出列，`output_column` 记被过滤的底层列名、`source_column` 记同一列，
以便在比对中稳定对账；过滤边的语义由 `condition_position` / `join_type` 元数据补充（不进四元组比对）。

---

## 1. v_bill_fee（JOIN 直取）

人工复核：4 个投影列分别直取 `orders o` / `lineitem l` / `customer c`，别名 `o/l/c` 归一后为
ORDERS / LINEITEM / CUSTOMER。两个 INNER JOIN 的 ON 条件共引用 4 个键列，均记作 FILTER 边（join-on, inner）。
结论：4 条 DIRECT + 4 条 ON FILTER，方向正确、无同名歧义（全部带限定名）。无待核对条目。

## 2. v_bill_fee_agg（SUM/COUNT/MAX/AVG + GROUP BY）

人工复核：`fee_total/detail_count/max_tax/avg_discount` 分别由 SUM/COUNT/MAX/AVG 产生，
函数名必须正确，来源列分别为 `l_extendedprice/l_orderkey/l_tax/l_discount`。
`patient_id` 是分组键 `o.o_custkey`：既是直取来源（DIRECT），又因 GROUP BY 产生分组依赖（AGGREGATE），
同一列并存两条不同 kind 的边。JOIN ON 键列补 FILTER。
结论：4 条函数 AGGREGATE + 1 条分组 AGGREGATE + 1 条 DIRECT + 2 条 ON FILTER。

## 3. v_bill_fee_window（ROW_NUMBER / SUM OVER）

人工复核：`fee_rank` 为 ROW_NUMBER() 窗口结果、`cust_fee_total` 为 SUM(...) OVER 窗口结果，
两者归 AGGREGATE 并列明函数名。两个窗口的 PARTITION BY 都是 `o.o_custkey`，去重后记作 1 条
分区 FILTER 边。`ORDER BY l.l_shipdate` 是**窗口排序键，不是取数来源**，不得出现在任何边的来源列里
（回归测试中单独断言「不存在 source_column = L_SHIPDATE 的边」）。JOIN ON 键列补 FILTER。
结论：2 直取 + 2 窗口 AGGREGATE + 1 分区 FILTER + 2 ON FILTER；排序列零误报。

## 4. v_inpatient_diag（CASE / UPPER 派生）

人工复核：`settle_status` 为 CASE WHEN 派生（引用 `o.o_orderstatus`，带 expression 摘要）、
`diag_group` 为 UPPER 函数派生（引用 `c.c_mktsegment`），两条 DERIVED。`bill_no` 直取 `o.o_orderkey`。
CASE 内的字符串常量 `'F' / '已结算' / '在途'` 与 WHEN/THEN/ELSE/END 关键字均不得误判为列。
结论：1 DIRECT + 2 DERIVED + 2 ON FILTER。

## 5. v_patient_basic（别名 + 限定名消歧）

人工复核：`customer a` / `nation b` 两表 JOIN，5 个投影列全部带 `a.`/`b.` 限定名，
按别名表归一为 CUSTOMER / NATION；`a.c_nationkey` 与 `b.n_nationkey` 以限定名区分，
无无限定名同名列，因此**不应出现 ambiguous 标记或 AMBIGUOUS_COLUMN 条目**。
结论：恰好 5 条 DIRECT + 2 条 ON FILTER，零歧义。

## 6. v_fee_detail_union（UNION ALL 列数一致）

人工复核：两分支投影均为 2 列且别名一致，列数一致可安全合并；两分支的边都要保留并以
`branchIndex=0/1` 区分（回归测试单独断言 branchIndex 取值）。分支 0 取 LINEITEM，分支 1 取 ORDERS，
WHERE 过滤列各补一条 FILTER。
结论：4 条跨分支 DIRECT + 2 条 WHERE FILTER；无冲突条目。

## 7. v_fee_detail_union_bad（UNION 分支列数不一致，异常样例）

人工复核：分支 0 有 2 列、分支 1 只有 1 列，列数冲突，**不能安全合并、不得只取其一**。
期望：登记 1 条 `UNION_BRANCH_CONFLICT` 待核对条目；两分支已能确定的边仍分别保留
（分支 0 的 bill_no / fee_amt、分支 1 的 bill_no），以便人工核对列对齐关系。
结论：3 条 DIRECT + 必需 review `union_branch_conflict`。

## 8. v_dept_cost_cte（CTE）

人工复核：`WITH cost_base AS (...)` 注册 COST_BASE 虚拟视图节点。CTE 体内 3 个投影列
（item_id/unit_cost/item_name）直取 PART / PARTSUPP，这 3 条边挂在 COST_BASE 名下；
外层 `FROM cost_base c` 作为消费方，`c.item_id`、`c.item_name` 连边到 COST_BASE 的输出列；
`adj_cost = c.unit_cost * 1.1` 为 DERIVED。CTE 体内 JOIN ON 键列补 FILTER。
结论：3 条 CTE 内 DIRECT + 2 条外层 DIRECT + 1 条 DERIVED + 2 条 ON FILTER。

## 9. v_cost_subquery（FROM 子查询别名归并）

人工复核：`FROM (SELECT ... FROM lineitem l) s` 的输出列 `bill_no/fee_amt` 通过别名 `s` 归并回
底层字段键 LINEITEM.L_ORDERKEY / LINEITEM.L_EXTENDEDPRICE；外层 `s.bill_no`、`s.fee_amt` 直取归并后的来源，
`s.fee_amt * 0.8` 为 DERIVED。别名归并生效后不应残留 `s.` 前缀冒充来源表。
结论：2 条归并后 DIRECT + 1 条 DERIVED。

## 10. v_settlement_star（SELECT *，异常样例）

人工复核：`SELECT * FROM lineitem l` 依赖表结构元数据，首版**不猜测展开**。
期望：仅 1 条 `output_column="*"`、`unresolved=true` 的不可解析标记边（来源表/列留空），
并登记 1 条 `SELECT_STAR` 待核对条目；未解析列数 = 1，条目数 = 1（对账一致）。
不得输出任何具体列名冒充已解析。
结论：1 条星号标记边 + 必需 review `select_star`。

## 11. v_group_pos（GROUP BY 1,2 位置引用）

人工复核：`GROUP BY 1, 2` 是位置引用，按投影序号解析为第 1 列 `o.o_orderpriority`、
第 2 列 `o.o_orderstatus`，分别给 `priority_code`、`status_code` 补一条分组依赖 AGGREGATE 边。
**不得出现名为 "1"/"2" 的列名**。`COUNT(*) AS bill_count` 为聚合结果（本快照不把它列为强制期望项，
回归测试单独断言聚合函数集合）。
结论：2 DIRECT + 2 位置引用解析出的分组 AGGREGATE；零字面序号列名。

## 12. v_filter_matrix（WHERE / JOIN ON / LEFT JOIN / AND-OR）

人工复核：3 个 LEFT JOIN，故 ON 边的 `join_type` 必须为 `left`；WHERE 条件
`o.o_orderdate >= DATE '2026-01-01' AND (o.o_orderstatus='F' OR o.o_totalprice > 1000)` 中，
`DATE '2026-01-01'`、`'F'`、`1000`、`DATE` 分别是类型转换字面量 / 字符串 / 数字 / 关键字，
**均不得误判为来源列**；AND-OR 组合只取 3 个直接引用列。3 个投影列直取 ORDERS / CUSTOMER / REGION。
结论：3 DIRECT + 6 条 ON FILTER(left) + 3 条 WHERE FILTER；零常量/函数/伪列误判。

---

## 复核一致性说明

- 以上 12 段的 `expected` 四元组与 `src/main/resources/samples/views/*.sql` 原文逐列核对，
  与解析器实现相互独立；若后续改动解析逻辑导致「期望项缺失」，`SqlParseGoldenTest` 会按
  「视图 / 列 / 边类型」列出具体差异项并使 12/12 覆盖断言失败。
- 星号、UNION 分支冲突等异常段的降级行为另见 `src/test/resources/edgecases/README.md`。
- 期望项采用「子集」语义：解析器允许产出比快照更多的合理边（如 COUNT(*) 结果边、
  未被列入的代表性过滤边），但快照中每条期望项必须命中，且 `required_review_reasons` 必须出现。
