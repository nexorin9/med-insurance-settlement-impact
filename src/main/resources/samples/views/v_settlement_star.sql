-- 样例取数视图：星号投影（异常样例，脱敏）
-- 覆盖：SELECT * 不猜测展开，输出 '*' 不可解析标记边并登记待人工核对条目
SELECT * FROM lineitem l
