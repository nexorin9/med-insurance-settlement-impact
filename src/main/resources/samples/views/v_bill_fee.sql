-- 样例取数视图：住院结算费用基础取数（脱敏，底层为 TPC-H 形态表）
-- 覆盖：多表 INNER JOIN、列别名直取、JOIN ON 条件列
SELECT o.o_orderkey AS bill_no,
       c.c_custkey AS patient_id,
       l.l_extendedprice AS fee_amt,
       l.l_tax AS tax_amt
  FROM orders o
  JOIN lineitem l ON o.o_orderkey = l.l_orderkey
  JOIN customer c ON o.o_custkey = c.c_custkey
