-- 样例取数视图：过滤条件矩阵（脱敏）
-- 覆盖：单表 WHERE、JOIN ON、LEFT JOIN 外连接、AND/OR 组合条件、常量与函数不误判为列
SELECT o.o_orderkey AS bill_no,
       c.c_name AS patient_name,
       r.r_name AS region_name
  FROM orders o
  LEFT JOIN customer c ON o.o_custkey = c.c_custkey
  LEFT JOIN nation n ON c.c_nationkey = n.n_nationkey
  LEFT JOIN region r ON n.n_regionkey = r.r_regionkey
 WHERE o.o_orderdate >= DATE '2026-01-01'
   AND (o.o_orderstatus = 'F' OR o.o_totalprice > 1000)
