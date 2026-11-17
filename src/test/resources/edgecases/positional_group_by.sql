-- 边界样例④：GROUP BY 位置引用歧义（序号 3 指向聚合结果列，不能作分组键）
-- 期望降级：登记 1 条 POSITIONAL_GROUP_BY 待人工核对条目；
--           不把字面序号 "3" 当成列名，也不把聚合列当作分组键静默处理。
-- 提示语：GROUP BY 位置引用 3 指向聚合/星号投影列，不能作为分组键，请人工核对
SELECT o.o_orderpriority AS priority_code,
       o.o_orderstatus AS status_code,
       COUNT(*) AS bill_count
  FROM orders o
 GROUP BY 3
