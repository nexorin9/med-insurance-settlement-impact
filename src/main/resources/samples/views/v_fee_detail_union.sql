-- 样例取数视图：两分支费用明细合并（脱敏）
-- 覆盖：UNION ALL 分支列数一致，输出列边集合取各分支并集并标 branchIndex
SELECT l.l_orderkey AS bill_no, l.l_extendedprice AS fee_amt
  FROM lineitem l
 WHERE l.l_linestatus = 'O'
UNION ALL
SELECT o.o_orderkey AS bill_no, o.o_totalprice AS fee_amt
  FROM orders o
 WHERE o.o_orderstatus = 'F'
