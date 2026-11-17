-- 边界样例①：SELECT * 星号投影
-- 期望降级：产出 output_column="*"、unresolved=true 的不可解析标记边 1 条，
--           并登记 1 条 SELECT_STAR 待人工核对条目；条目数 = 未解析列数 = 1。
--           绝不猜测展开、绝不输出具体列名冒充已解析。
-- 提示语：SELECT * 依赖表结构元数据，首版不展开，请人工确认输出列及其来源
SELECT * FROM customer c
