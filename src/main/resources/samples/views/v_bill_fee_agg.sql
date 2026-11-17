-- 样例取数视图：费用合计与明细笔数（脱敏）
-- 覆盖：SUM / COUNT / MAX 聚合、GROUP BY 分组键派生、聚合结果直接输出
SELECT o.o_custkey AS patient_id,
       SUM(l.l_extendedprice) AS fee_total,
       COUNT(l.l_orderkey) AS detail_count,
       MAX(l.l_tax) AS max_tax,
       AVG(l.l_discount) AS avg_discount
  FROM orders o
  JOIN lineitem l ON o.o_orderkey = l.l_orderkey
 GROUP BY o.o_custkey
