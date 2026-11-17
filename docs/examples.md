# 输出样例与真实终端回显

> 本文放完整回显与导出片段，`README.md` 只留速查。所有内容来自脱敏样例的真实运行，批次一律 `<EXAMPLE_BATCH>` 占位。

## 1. version / doctor

```text
$ java -jar target/med-insurance-settlement-impact.jar version
医保结算清单取数影响分析 v0.1.0

$ java -Djava.awt.headless=true -jar target/med-insurance-settlement-impact.jar doctor
医保结算清单取数影响分析 v0.1.0

环境变量自检（只报变量名与是否已设，不回显取值）：
  HIS_DB_URL                   [未设置]    未设置
  HIS_DB_USER                  [未设置]    未设置
  HIS_DB_PASSWORD              [未设置]    未设置
  IFACE_REGISTRY_BASE_URL      [未设置]    未设置
  IFACE_REGISTRY_TOKEN         [未设置]    未设置
  MODEL_BASE_URL               [未设置]    未设置
  MODEL_API_KEY                [未设置]    未设置
  MODEL_NAME                   [未设置]    未设置
  MODEL_TIMEOUT_SECONDS        [未设置]    未设置，将使用默认值 30 秒

运行时：Java 17.0.20.1（需要 17 及以上）
驱动：Druid 可用 / SQLite JDBC 可用
显示环境：headless（不创建窗口）
连通性：院内 HIS 只读库 / 院内接口管理平台 / 模型端点本轮均未实连，仅做协议兼容测试。

有变量尚未设置：请在系统环境或 .env 中补齐后重试，详见 .env.example。
```

`doctor` 退出码 `1`（无凭据时符合预期）。输出中没有出现任何变量取值。

## 2. parse-view · 列级边 JSON

```text
$ java -jar ... parse-view --sql src/main/resources/samples/views/v_bill_fee.sql --out /tmp/edges.json
视图：V_BILL_FEE，方言：Oracle
SQL 摘要：ee09e66e243d75f8133974ffd455a721df0f80047e214bc8bf756953845a9070
列级边：8 条（直取 4 / 派生 0 / 聚合 0 / 过滤 4）
待人工核对：0 条
已写出：/tmp/edges.json
```

`/tmp/edges.json` 的一条过滤边（JOIN ON 条件列）：

```json
{
  "output_column": "O_ORDERKEY",
  "source_table": "ORDERS",
  "source_column": "O_ORDERKEY",
  "edge_kind": "FILTER",
  "condition_position": "join-on",
  "join_type": "inner",
  "confidence": 0.5
}
```

## 3. analyze · 主流程回显

```text
$ java -jar ... analyze --view V_BILL_FEE --sql .../v_bill_fee.sql --field FEE_AMT \
    --out /tmp/impact.csv --note /tmp/vendor-note.md
医保结算清单取数影响分析 · 主流程结果
视图 / 改动列：V_BILL_FEE / FEE_AMT
口径：严格口径（strict）
SQL 摘要：ee09e66e243d75f8133974ffd455a721df0f80047e214bc8bf756953845a9070
影响面记录：1 行
状态：待人工确认，降级：否，待人工核对：0 条
回写开关：关闭（默认）
主流程步骤：读取视图定义 → SQL 解析 → 图构建 → 下游可达 → 清单对齐 → 归因 → 待确认 → 人工确认 → 幂等回写
视图 V_BILL_FEE.FEE_AMT｜状态 PENDING_CONFIRM｜记录 1 条｜幂等键 4492dfaf15a91e0c42b11ff67f048ac0d7c28534d2081b5f3544fac080c6e348
· 归因：列名精确匹配走确定性层，未调用模型（可注入第二个实现）
· 待确认：记录状态 PENDING_CONFIRM，等待人工逐行确认
影响面 CSV 已写出：/tmp/impact.csv
厂商说明草稿已写出：/tmp/vendor-note.md
```

## 4. 影响面 CSV（UTF-8 BOM，Excel 直接打开）

```csv
分区,字段,来源列,影响类型,置信度,口径,跳数,批次,判定,理由
医疗收费信息,医疗总费用金额合计,医疗总费用金额合计,direct,1.00,strict,1,<EXAMPLE_BATCH>,实质受影响,【严格口径/strict】改动 V_BILL_FEE.FEE_AMT（逻辑变更）沿 1 跳波及 LIST_FIELD.医疗总费用金额合计
```

## 5. 厂商说明草稿（真实导出片段）

```markdown
# 取数视图 V_BILL_FEE 改动影响说明 · 草稿（未人工确认）

> 本文为草稿（未人工确认），需人工逐行确认后方可回写台账。

## 改动点
- 视图：V_BILL_FEE
- 改动列：FEE_AMT
- 改动类型：逻辑变更
- 改动说明：取数视图口径调整，需核对结算清单波及面
- 视图摘要：ee09e66e…
- 计算口径：严格口径
- 归因状态：归因已生成

## 受影响结算清单字段（严格口径）
| 分区 | 字段 | 来源列 | 判定 | 置信度 | 口径 | 跳数 |
|---|---|---|---|---|---|---|
| 医疗收费信息 | 医疗总费用金额合计 | 医疗总费用金额合计 | 实质受影响 | 1.00 | 严格口径 | 1 |

## 批次
- <EXAMPLE_BATCH>
```

## 6. 待人工核对清单（星号投影）

```text
$ java -jar ... export --kind review --sql .../v_settlement_star.sql --out /tmp/review.json
待人工核对清单已写出：/tmp/review.json（1 条）
```

```json
{
  "view_name": "V_SETTLEMENT_STAR",
  "sql_digest": "e15ff07ca52fd8c59029853756c40caf76ec8b67c26fe6bd4b1518c6b80aeb68",
  "review_items": [{
    "source": "V_SETTLEMENT_STAR",
    "position": "第 3 行第 8 列",
    "reason": "select_star",
    "reason_zh": "SELECT * 依赖表结构元数据，首版不展开",
    "hint": "SELECT * 依赖表结构元数据，首版不展开，请人工确认输出列及其来源"
  }],
  "count": 1
}
```

## 7. 实测截图

| 文件 | 内容 |
|------|------|
| `docs/screenshots/01-main-workbench.png` | 真实桌面窗口：顶栏 12 / 1 / 0、左视图树 12 段、中央影响面表 1 行、右详情逐跳回溯、底部状态条 |
| `docs/screenshots/02-impact-detail.png` | 同一画面局部：中央表与右详情证据链 |
| `docs/screenshots/03-vendor-note.png` | `analyze --note` 真实导出文件的片段排版展示（非界面截图） |

截图采集时确实启动了桌面应用并跑了一次 `analyze`；目标与实测的差距在 `README.md` 的「概念 / 实测对照」里如实标注。
