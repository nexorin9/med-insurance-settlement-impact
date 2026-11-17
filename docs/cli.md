# CLI 与接口参考

> 命令行是运维与排障通道；医院岗位的日常操作在三栏工作台里完成。本文是参数手册，产品说明见 `README.md`。

## 1. 通用约定

```bash
java -jar target/med-insurance-settlement-impact.jar <子命令> [--参数 值]
```

- 无参数且有显示环境：打开桌面窗口并常驻托盘；无显示环境自动进入 headless 模式，只打印用法。
- 所有子命令支持 `--help`；未知子命令退出码 `2`；执行失败退出码 `1`；成功 `0`。
- `doctor` 在环境变量未齐时退出码 `1`，这是**预期行为**，输出中只有变量名与是否已设，不回显取值。
- 路径参数均为本机路径；不指定 `--sql` 时 `analyze` 读取内置样例视图（12 段之一）。
- 批次一律用 `<EXAMPLE_BATCH>` 占位，不编造具体批次号。

## 2. 子命令参数

| 子命令 | 参数 | 必填 | 默认 | 说明 |
|--------|------|------|------|------|
| `version` | 无 | 否 | | 打印产品名与版本 |
| `doctor` | 无 | 否 | | 9 项变量、Java 版本、驱动、显示环境、连通性边界 |
| `parse-view` | `--sql <file>` | 是 | | 待解析的视图 SQL 文件 |
| | `--out <json>` | 是 | | 列级边 JSON 输出路径 |
| `analyze` | `--view <name>` | 否 | `V_BILL_FEE` | 视图名，自动大写 |
| | `--sql <file>` | 否 | 内置同名样例 | 视图 SQL 文本 |
| | `--field <col>` | 否 | `FEE_AMT` | 被改动的列名，自动大写 |
| | `--scope strict\|conservative` | 否 | `strict` | 严格口径只走直取 / 派生 / 聚合边；保守口径并入过滤边 |
| | `--change-kind` | 否 | `logic_changed` | `renamed` / `logic_changed` / `filter_added` / `dropped` / `added` |
| | `--note-zh <text>` | 否 | 内置说明 | 中文改动说明，供归因与厂商说明使用 |
| | `--out <csv>` | 否 | 不写文件 | 影响面 CSV（UTF-8 带 BOM） |
| | `--note <md>` | 否 | 不写文件 | 厂商中文说明草稿 |
| `export` | `--kind review\|change-order` | 否 | `review` | 待人工核对清单 / 变更单 |
| | `--sql <file>` | `review` 时必填 | | 视图 SQL |
| | `--out <path>` | 否 | 标准输出 / `data/` | 清单 JSON 或变更单导出目录 |
| `diff` | `--old-sql <file>` | 是 | | 上一版视图 SQL |
| | `--new-sql <file>` | 是 | | 本版视图 SQL |
| | `--out <csv>` | 否 | 标准输出 | 差异清单 CSV |
| `gui` | 无 | 否 | | 显式打开桌面窗口（headless 下打印降级提示） |

## 3. 可复制命令序列

```bash
# 构建
mvn -B clean package

# 自检
java -jar target/med-insurance-settlement-impact.jar doctor

# 最窄主路径：一段 SQL → 可核对的列级边 JSON
java -jar target/med-insurance-settlement-impact.jar parse-view \
  --sql src/main/resources/samples/views/v_bill_fee.sql --out /tmp/edges.json

# 完整主流程：影响面 CSV + 厂商说明草稿
java -jar target/med-insurance-settlement-impact.jar analyze \
  --view V_BILL_FEE --sql src/main/resources/samples/views/v_bill_fee.sql \
  --field FEE_AMT --scope conservative --out /tmp/impact.csv --note /tmp/vendor-note.md

# 待人工核对清单（星号投影）
java -jar target/med-insurance-settlement-impact.jar export --kind review \
  --sql src/main/resources/samples/views/v_settlement_star.sql --out /tmp/review.json

# 变更单降级导出（院内无写接口时由专员手工录入台账）
java -jar target/med-insurance-settlement-impact.jar export --kind change-order --out /tmp/orders

# 两版影响面差异回放
java -jar target/med-insurance-settlement-impact.jar diff \
  --old-sql src/main/resources/samples/views/v_bill_fee.sql \
  --new-sql src/main/resources/samples/views/v_bill_fee_agg.sql --out /tmp/diff.csv

# 只打印帮助，不创建窗口
java -Djava.awt.headless=true -jar target/med-insurance-settlement-impact.jar analyze --help
```

## 4. parse-view 输出结构

```json
{
  "view_name": "V_BILL_FEE",
  "dialect": "oracle",
  "sql_digest": "<64 位十六进制，规范化 SQL 后取 sha256>",
  "edges": [
    {
      "output_column": "FEE_AMT",
      "source_table": "LINEITEM",
      "source_column": "L_EXTENDEDPRICE",
      "edge_kind": "DIRECT",
      "view_name": "V_BILL_FEE",
      "expression": "",
      "aggregate_function": "",
      "distinct": false,
      "condition_position": "",
      "join_type": "",
      "branch_index": 0,
      "line": 5,
      "column": 8,
      "unresolved": false,
      "ambiguous": false,
      "confidence": 1.0
    }
  ],
  "review_items": [],
  "errors": [],
  "report": {
    "total_segments": 1,
    "success_segments": 1,
    "failed_segments": 0,
    "degraded_segments": 0,
    "edge_count": 8,
    "review_item_count": 0,
    "counts_by_kind": {"DIRECT": 4, "DERIVED": 0, "AGGREGATE": 0, "FILTER": 4}
  },
  "success": true
}
```

`edge_kind` 四取值为 `DIRECT` / `DERIVED` / `AGGREGATE` / `FILTER`。`FILTER` 边带 `condition_position`（`where` / `join-on`）与 `join_type`（`inner` / `left` / `right` / `full`），在严格口径下不参与下游可达扩展。`unresolved=true` 的 `*` 边表示星号投影不展开，同时必有对应 `review_items` 条目。

## 5. 院内接口管理平台（正式协议，本轮未实连）

| 方向 | 方法与路径 | 头 | 说明 |
|------|------------|----|------|
| 读 | `GET {IFACE_REGISTRY_BASE_URL}/api/v1/interface-entries?system=HIS` | `Authorization: Bearer $IFACE_REGISTRY_TOKEN` | 查已有变更记录 |
| 写 | `POST {IFACE_REGISTRY_BASE_URL}/api/v1/interface-entries/{entry_id}/field-change-impacts` | 同上 + `Idempotency-Key: {idempotency_key}` + `Content-Type: application/json` | 回写一条变更记录；同键重复提交只落一条 |

写请求体字段（与 `spec.md` §2.4 一致）：`idempotency_key` / `view_name` / `view_dialect` / `sql_digest` / `changed_field` / `change_kind` / `affected_list_fields[]`（每项 `section` / `field` / `source_column` / `impact` / `confidence` / `batch`）/ `affected_batches[]` / `summary_zh` / `confirmed_by` / `confirmed_at` / `occurred_at`。

模型端点统一为 `POST {MODEL_BASE_URL}/v1/chat/completions`，请求含 `model` / `messages` / `temperature` 与结构化输出开关，响应按 OpenAI 兼容格式解析。**业务层只依赖单一网关接口**，换一家模型服务不需要改业务代码。

## 6. 退出码与错误定位

| 退出码 | 含义 |
|--------|------|
| `0` | 成功 |
| `1` | 执行失败，或 `doctor` 检测到变量未齐；解析失败时标准错误含「第 X 行第 Y 列」 |
| `2` | 参数缺失、文件不存在或未知子命令 |

六类模型错误归一为固定错误码：`MODEL_CONNECT_TIMEOUT` / `MODEL_READ_TIMEOUT` / `MODEL_HTTP_4XX` / `MODEL_HTTP_5XX` / `MODEL_EMPTY_RESPONSE` / `MODEL_SCHEMA_INVALID`。仅超时与 5xx 做有限退避重试（≤2 次），4xx 与结构不合规不重试。
