-- 边界样例②：UNION ALL 两分支列数不一致
-- 期望降级：登记 1 条 UNION_BRANCH_CONFLICT 待人工核对条目；
--           两分支各自已能确定的边分别保留（branchIndex=0/1），不得只取其一冒充全部；
--           不得静默合并，解析结果仍为成功但计入降级段。
-- 提示语：UNION 分支列数不一致，不能安全合并；各分支边已分别保留，请人工核对分支列对齐关系。
SELECT l.l_orderkey AS bill_no, l.l_extendedprice AS fee_amt
  FROM lineitem l
UNION ALL
SELECT o.o_orderkey AS bill_no
  FROM orders o
