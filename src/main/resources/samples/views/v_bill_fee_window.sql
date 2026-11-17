-- 样例取数视图：费用窗口排名与按人累计（脱敏）
-- 覆盖：ROW_NUMBER / SUM OVER 窗口函数、PARTITION BY 分区键、ORDER BY 排序列
SELECT o.o_orderkey AS bill_no,
       l.l_extendedprice AS fee_amt,
       ROW_NUMBER() OVER (PARTITION BY o.o_custkey ORDER BY l.l_shipdate) AS fee_rank,
       SUM(l.l_extendedprice) OVER (PARTITION BY o.o_custkey) AS cust_fee_total
  FROM orders o
  JOIN lineitem l ON o.o_orderkey = l.l_orderkey
