# lineage 基线 vs 本产品对比基准

本文件由 `LineageBaselineBenchmarkTest` 在 12 张样例图上自动复跑生成，对比源产品「无向连通分量」基线与本产品「沿 DOWNSTREAM 的下游可达子集」。

## 结论

- 本产品受影响集合 ⊆ 基线受影响集合：基线把上游一并框进连通分量，本产品只沿 DOWNSTREAM 取可达子集，故恒为子集。
- 存在多报字段数 > 0 的用例：下表「多报」列均大于 0（含改动点本身与上游），证明两种口径不是同一实现换名。
- 保守口径并入 FILTER 边，受影响字段数 ≥ 严格口径；多出的恰为过滤边带来的字段。
- 图规模覆盖 JOIN 扇入、SUM/聚合、CTE 消费与含环图；平均耗时见文末。

## 差异统计表（12 张样例图）

| 图 | 形态 | 节点 | 边 | 严格受影响 | 保守受影响 | 基线受影响 | 多报 | 命中率 | 含环 | 耗时(ms) | 多报字段清单样本 |
|---|---|---:|---:|---:|---:|---:|---:|---:|:---:|---:|---|
| G0 | JOIN+CTE+环 | 11 | 11 | 6 | 8 | 11 | 3 | 72.7% | true | 0.662 | V0.SEED / SRC0.U / JOIN0.K |
| G1 | JOIN+CTE | 12 | 11 | 7 | 9 | 12 | 3 | 75.0% | false | 0.121 | V1.SEED / SRC1.U / JOIN1.K |
| G2 | SUM聚合+CTE | 13 | 12 | 8 | 10 | 13 | 3 | 76.9% | false | 0.129 | V2.SEED / SRC2.U / JOIN2.K |
| G3 | JOIN+CTE+环 | 11 | 11 | 6 | 8 | 11 | 3 | 72.7% | true | 0.107 | V3.SEED / SRC3.U / JOIN3.K |
| G4 | JOIN+聚合 | 12 | 11 | 7 | 9 | 12 | 3 | 75.0% | false | 0.162 | V4.SEED / SRC4.U / JOIN4.K |
| G5 | CTE+聚合 | 13 | 12 | 8 | 10 | 13 | 3 | 76.9% | false | 0.181 | V5.SEED / SRC5.U / JOIN5.K |
| G6 | JOIN+CTE+环 | 11 | 11 | 6 | 8 | 11 | 3 | 72.7% | true | 0.180 | V6.SEED / SRC6.U / JOIN6.K |
| G7 | JOIN+CTE | 12 | 11 | 7 | 9 | 12 | 3 | 75.0% | false | 0.068 | V7.SEED / SRC7.U / JOIN7.K |
| G8 | SUM聚合+CTE | 13 | 12 | 8 | 10 | 13 | 3 | 76.9% | false | 0.080 | V8.SEED / SRC8.U / JOIN8.K |
| G9 | JOIN+CTE+环 | 11 | 11 | 6 | 8 | 11 | 3 | 72.7% | true | 0.061 | V9.SEED / SRC9.U / JOIN9.K |
| G10 | JOIN+聚合 | 12 | 11 | 7 | 9 | 12 | 3 | 75.0% | false | 0.068 | V10.SEED / SRC10.U / JOIN10.K |
| G11 | CTE+聚合 | 13 | 12 | 8 | 10 | 13 | 3 | 76.9% | false | 0.075 | V11.SEED / SRC11.U / JOIN11.K |

说明：命中率 = 保守口径受影响字段数 / 基线受影响字段数；多报 = 基线受影响集合 − 保守口径受影响集合（基线多框入上游与改动点本身）。

12 张图三种计算的平均合计耗时：0.158 ms（机器相关，仅供回归对照）。

## 复跑命令

```
mvn -q test -Dtest=LineageBaselineBenchmarkTest
mvn -q test -Dtest=ImpactTraceBaselineTest
mvn -q test -Dtest=LineageScaleTest
```

复跑会在项目根工作目录下覆盖本文件；如需固定工作目录，请在项目根执行上述命令。
