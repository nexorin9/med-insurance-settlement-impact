-- 样例取数视图：内嵌子查询后再派生（脱敏）
-- 覆盖：FROM 后子查询别名、输出列按别名归并回同一字段键、外层表达式派生
SELECT s.bill_no AS bill_no,
       s.fee_amt AS fee_amt,
       s.fee_amt * 0.8 AS discounted_amt
  FROM (SELECT l.l_orderkey AS bill_no,
               l.l_extendedprice AS fee_amt
          FROM lineitem l) s
