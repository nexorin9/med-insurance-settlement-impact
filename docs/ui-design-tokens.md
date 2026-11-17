# UI 设计 token 说明 · report + ui 模块

> 本文件记录 `ui/Theme` 与 `report` 输出层的设计取舍，供集成负责人与内审核对。
> 所有常量集中在 `Theme`，不在组件里散落硬编码。

## 1. 配色（集中在 `Theme`，与 `docs/concepts/visual-target.md` 对齐）

| 用途 | token | 取值 |
|---|---|---|
| 冷灰底 | `BG` | `#F4F6F9` |
| 深蓝主色 | `PRIMARY` | `#1F4E79` |
| 成功 | `SUCCESS` | `#2E7D32` |
| 警告 | `WARNING` | `#B26A00` |
| 危险 | `DANGER` | `#C62828` |
| 中性灰 | `NEUTRAL` | `#5A6673` |
| 卡片底 | `CARD_BG` | `#FFFFFF` |
| 严格口径色标 | `STRICT_MARK` | `#1565C0` |
| 保守口径色标 | `CONSERVATIVE_MARK` | `#B26A00` |

严格 / 保守口径用**不同色标**，避免把保守口径多出的疑似波及误读成实质影响。

## 2. 间距与字体层级

- 8px 栅格：`GRID = 8`，派生 `SPACE_HALF=4 / SPACE_1=8 / SPACE_2=16 / SPACE_3=24 / SPACE_4=32`；
  行高 32、单元格左右内边距 12，保证一屏看几十行。
- 五级字号：`FONT_TITLE=18 / FONT_SUBTITLE=14 / FONT_BODY=13 / FONT_TABLE=12 / FONT_MONO=12`。
- 字体族：`FONT_FAMILY_UI` 为 Microsoft YaHei / PingFang SC / Source Han Sans SC / Noto Sans CJK SC /
  sans-serif；`FONT_FAMILY_MONO` 为等宽字体栈，用于**字段名、摘要与幂等键**逐字符核对。
- `FontFallback.resolve(...)` 优先系统可用中文字体，缺失按 fallback 链逐级尝试并给提示；
  末端是 Java 保证可用的逻辑字体 `SansSerif`。高 DPI 下 `scaled*()` 按系数调整列宽与行高。

## 3. 控件状态（可枚举）

- `Theme.ButtonState`：`NORMAL / HOVER / ACTIVE / DISABLED / LOADING`（按钮与输入框四态 + 加载态）。
- `Theme.RowState`：`NORMAL / SELECTED / HOVER / DISABLED`（表格行三态 + 常态）。
- 每态都有 `colorFor(...)` 返回对应色值 token；危险操作按钮使用 `DANGER` 并带 `DANGER_ICON_MARK`
  文本图标提示（不使用 emoji、不引入图标库）。

## 4. headless 安全约定

- `UiModel` 是**纯数据**，不含 AWT/Swing 类型；所有布局、计数、文案、菜单、响应式档位都沉淀在它里面。
- `MainFrame extends JFrame`，**仅在非 headless 构造**；类加载期不创建窗口对象。
- `Theme.apply()` 在 headless 下只登记状态位，不安装 Look and Feel、不碰 `UIManager`、不抛异常。
- `TrayController` 的菜单、关窗隐藏、气泡、降级提示均为纯状态模型，可脱离 `SystemTray` 断言；
  真实托盘图标只在 `install()`（非 headless）里创建。
- 测试全部 JUnit5：`Assumptions` / `@TempDir` / 纯数据断言，不 `new JFrame()`、不弹对话框、不访问显示设备。

## 5. report 输出层规范

- 批次一律 `<EXAMPLE_BATCH>`：`ReportText.safeBatch` 对任何上游批次字面量归一脱敏，说明、CSV、汇总均不泄露。
- CSV 列：`分区,字段,来源列,影响类型,置信度,口径,跳数,批次,判定,理由`；UTF-8 BOM；RFC4180 转义，
  理由里的逗号 / 双引号 / 换行不影响列切分。
- `TwoVersionDiff` 以「分区 + 字段名」为主键，五类差异：added / removed / verdictChanged /
  scopeChanged / sourceChanged；来源列改名归入 `sourceChanged`，同名不同分区不误判。
- 厂商说明为「草稿（未人工确认）」，逐条理由原样转述 `NoteContext.evidence` 短句与 `ImpactRecord.reasonZh()`，
  不编造措辞；不含患者明细、真实批次号与凭据。
