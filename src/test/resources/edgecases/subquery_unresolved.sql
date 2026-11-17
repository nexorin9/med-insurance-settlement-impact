-- 边界样例③：FROM 子查询无法安全归并（子查询内 SELECT *，输出列无法确定）
-- 期望降级：登记 1 条 SUBQUERY_UNRESOLVED 待人工核对条目；
--           外层引用该子查询别名时只产 unresolved 标记边，不得冒充具体来源列。
-- 提示语：FROM 子查询输出列无法确定来源或缺少别名，不能安全归并，请人工核对子查询投影列
SELECT merged.bill_no AS bill_no
  FROM (SELECT * FROM lineitem l) merged
