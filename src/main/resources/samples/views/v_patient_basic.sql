-- 样例取数视图：患者基础信息与地区（脱敏）
-- 覆盖：表别名、同名列以限定名消歧（a.c_nationkey / b.n_nationkey）
SELECT a.c_custkey AS patient_id,
       a.c_name AS patient_name,
       b.n_name AS nation_name,
       a.c_nationkey AS nation_key_a,
       b.n_nationkey AS nation_key_b
  FROM customer a
  JOIN nation b ON a.c_nationkey = b.n_nationkey
