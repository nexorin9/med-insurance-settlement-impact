-- 样例取数视图：结算状态与诊断分组派生（脱敏）
-- 覆盖：CASE WHEN 派生、函数派生、表达式摘要
SELECT o.o_orderkey AS bill_no,
       CASE WHEN o.o_orderstatus = 'F' THEN '已结算' ELSE '在途' END AS settle_status,
       UPPER(c.c_mktsegment) AS diag_group
  FROM orders o
  JOIN customer c ON o.o_custkey = c.c_custkey
