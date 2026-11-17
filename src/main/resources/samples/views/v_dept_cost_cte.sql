-- 样例取数视图：项目成本 CTE 复用（脱敏）
-- 覆盖：WITH ... AS 注册虚拟视图节点、CTE 内列边、外层消费 CTE 输出列
WITH cost_base AS (
  SELECT p.p_partkey AS item_id,
         ps.ps_supplycost AS unit_cost,
         p.p_name AS item_name
    FROM part p
    JOIN partsupp ps ON p.p_partkey = ps.ps_partkey
)
SELECT c.item_id AS item_id,
       c.item_name AS item_name,
       c.unit_cost * 1.1 AS adj_cost
  FROM cost_base c
