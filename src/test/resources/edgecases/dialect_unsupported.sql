-- 边界样例⑤：方言不支持（MySQL 反引号 + LIMIT）
-- 期望降级：SqlDialect.detect 判为 MYSQL，parse 返回 failure 且带行列号错误；
--           登记 1 条 DIALECT_UNSUPPORTED 待人工核对条目；明确「首版不支持」而非猜测解析。
-- 提示语：首版仅支持 Oracle 视图方言，检测到 MySQL 特有语法（反引号 / LIMIT / ON DUPLICATE KEY / :: 等）
SELECT `o_orderkey` AS bill_no, `o_totalprice` AS fee_amt FROM orders LIMIT 10
