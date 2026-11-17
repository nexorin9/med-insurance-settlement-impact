-- 样例取数视图：列数不一致的 UNION 分支（异常样例，脱敏）
-- 覆盖：分支列数冲突时不合并，整段登记为待人工核对条目
SELECT l.l_orderkey AS bill_no, l.l_extendedprice AS fee_amt
  FROM lineitem l
UNION ALL
SELECT o.o_orderkey AS bill_no
  FROM orders o
