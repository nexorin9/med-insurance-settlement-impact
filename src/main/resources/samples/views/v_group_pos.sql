-- 样例取数视图：GROUP BY 位置引用（脱敏）
-- 覆盖：GROUP BY 1,2 按投影列序号解析为实际列表达式，不把字面序号当列名
SELECT o.o_orderpriority AS priority_code,
       o.o_orderstatus AS status_code,
       COUNT(*) AS bill_count
  FROM orders o
 GROUP BY 1, 2
