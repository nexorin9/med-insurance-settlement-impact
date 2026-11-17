package com.chaosforge.medins.lineage;

import com.chaosforge.medins.model.EdgeKind;
import com.chaosforge.medins.model.FieldKey;
import com.chaosforge.medins.model.ImpactScope;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.chaosforge.medins.lineage.LineageFixtures.edge;
import static com.chaosforge.medins.lineage.LineageFixtures.fk;
import static com.chaosforge.medins.lineage.LineageFixtures.keys;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task22（深度验收 b）：以「无向连通分量」为基线，在 ≥12 张样例图上与下游可达子集对比。
 *
 * <p>样例图覆盖 JOIN 扇入 / 聚合 / CTE 消费 / 环。断言本产品受影响集合 ⊆ 基线集合，
 * 且存在多报字段数 > 0 的用例；同时把可复现的差异统计表写入 docs/lineage-baseline-benchmark.md。
 */
class LineageBaselineBenchmarkTest {

    /** 12 张样例图的形态标注（覆盖 JOIN / 聚合 / CTE / 环）。 */
    private static final String[] SHAPES = {
            "JOIN+CTE+环", "JOIN+CTE", "SUM聚合+CTE", "JOIN+CTE+环",
            "JOIN+聚合", "CTE+聚合", "JOIN+CTE+环", "JOIN+CTE",
            "SUM聚合+CTE", "JOIN+CTE+环", "JOIN+聚合", "CTE+聚合"};

    /** 构造第 i 张样例图：含上游、严格链、JOIN 扇入、CTE 消费、FILTER 分支，部分含环。 */
    private FieldLineageGraph sample(int i) {
        String v = "V" + i;
        FieldLineageGraph g = new FieldLineageGraph();
        // 上游：保证基线会多框入上游
        edge(g, "SRC" + i + ".U", v + ".SEED", EdgeKind.DIRECT);
        // 严格下游链，长度 3..5，交错直取/派生，部分图在第 1 跳用聚合
        String prev = v + ".SEED";
        int len = 3 + (i % 3);
        for (int j = 0; j < len; j++) {
            String n = v + ".D" + j;
            EdgeKind k = (i % 4 == 2 && j == 1) ? EdgeKind.AGGREGATE
                    : (j % 2 == 0 ? EdgeKind.DIRECT : EdgeKind.DERIVED);
            edge(g, prev, n, k);
            prev = n;
        }
        // JOIN 扇入：底层表列与链上节点共同汇入 v.J
        edge(g, "JOIN" + i + ".K", v + ".J", EdgeKind.DIRECT);
        edge(g, v + ".D0", v + ".J", EdgeKind.DERIVED);
        // CTE 消费：SEED -> CTE.C -> v.CTEOUT
        edge(g, "CTE" + i + ".C", v + ".CTEOUT", EdgeKind.DERIVED);
        edge(g, v + ".SEED", "CTE" + i + ".C", EdgeKind.DIRECT);
        // FILTER 分支：保守口径比严格口径多出的字段
        edge(g, v + ".SEED", v + ".F", EdgeKind.FILTER);
        edge(g, v + ".F", v + ".FD", EdgeKind.DIRECT);
        // 部分图成环：D0->D1 已存在，补一条 D1->D0
        if (i % 3 == 0) {
            edge(g, v + ".D1", v + ".D0", EdgeKind.DIRECT);
        }
        return g;
    }

    @Test
    void twelveGraphsProductSubsetOfBaselineAndOverReportingExists() {
        assertTrue(SHAPES.length >= 12, "至少 12 张样例图");

        List<String[]> rows = new ArrayList<>();
        boolean anyOverReport = false;
        double totalMs = 0.0d;

        for (int i = 0; i < SHAPES.length; i++) {
            FieldLineageGraph g = sample(i);
            FieldKey seed = fk("V" + i + ".SEED");

            long t0 = System.nanoTime();
            ReachabilityResult sr = DownstreamReachability.reachable(g, seed, ImpactScope.STRICT);
            long strictNs = System.nanoTime() - t0;
            long t1 = System.nanoTime();
            ReachabilityResult cr = DownstreamReachability.reachable(g, seed, ImpactScope.CONSERVATIVE);
            long consNs = System.nanoTime() - t1;
            long t2 = System.nanoTime();
            Set<FieldKey> baseline = BaselineConnectedComponents.component(g, seed);
            long baseNs = System.nanoTime() - t2;
            double ms = (strictNs + consNs + baseNs) / 1_000_000.0d;
            totalMs += ms;

            // 核心断言：本产品受影响集合 ⊆ 基线集合（基线多框入上游）
            assertTrue(baseline.containsAll(sr.fields()),
                    "图 " + i + "：严格口径集合应为基线子集");
            assertTrue(baseline.containsAll(cr.fields()),
                    "图 " + i + "：保守口径集合应为基线子集");

            LinkedHashSet<FieldKey> over = new LinkedHashSet<>(baseline);
            over.removeAll(cr.fields());
            if (!over.isEmpty()) {
                anyOverReport = true;
            }

            // 双口径差异与「保守-严格」一致
            LinkedHashSet<FieldKey> expectedDiff = new LinkedHashSet<>(cr.fields());
            expectedDiff.removeAll(sr.fields());
            assertEquals(expectedDiff, DualModeDiff.extraConservativeFields(g, seed),
                    "图 " + i + "：多出集合应恰为保守比严格多出的字段");

            double hit = baseline.isEmpty() ? 0.0d : (double) cr.fields().size() / baseline.size();
            List<String> sampleOver = new ArrayList<>(keys(over));
            if (sampleOver.size() > 3) {
                sampleOver = sampleOver.subList(0, 3);
            }
            rows.add(new String[]{
                    "G" + i, SHAPES[i],
                    String.valueOf(g.nodes().size()), String.valueOf(g.edgeCount()),
                    String.valueOf(sr.fields().size()), String.valueOf(cr.fields().size()),
                    String.valueOf(baseline.size()), String.valueOf(over.size()),
                    String.format(Locale.ROOT, "%.1f%%", hit * 100),
                    String.valueOf(cr.containsCycle()),
                    String.format(Locale.ROOT, "%.3f", ms),
                    String.join(" / ", sampleOver)});
        }

        assertTrue(anyOverReport, "必须存在多报字段数 > 0 的用例，证明两种口径确有实质区别");
        String md = renderDoc(rows, totalMs / SHAPES.length);
        assertTrue(md.contains("复跑命令"));
        assertTrue(md.contains("本产品受影响集合 ⊆ 基线受影响集合"));

        Path doc = Paths.get("docs/lineage-baseline-benchmark.md");
        try {
            if (doc.getParent() != null) {
                Files.createDirectories(doc.getParent());
            }
            Files.writeString(doc, md, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            // 工作目录或权限异常时打印，不让测试因文档写入而硬失败
            System.err.println("benchmark 文档写入失败：" + ex.getMessage());
        }
        assertTrue(Files.exists(doc), "docs/lineage-baseline-benchmark.md 应已生成");
        try {
            String onDisk = Files.readString(doc, StandardCharsets.UTF_8);
            assertTrue(onDisk.contains("复跑命令"), "文档应含复跑命令");
            assertTrue(onDisk.contains("差异统计表"), "文档应含差异统计表");
            assertTrue(onDisk.contains("| G0 |"), "文档应含 12 张图的对比行");
        } catch (IOException ex) {
            System.err.println("benchmark 文档回读失败：" + ex.getMessage());
        }
    }

    private String renderDoc(List<String[]> rows, double avgMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("# lineage 基线 vs 本产品对比基准\n\n");
        sb.append("本文件由 `LineageBaselineBenchmarkTest` 在 12 张样例图上自动复跑生成，")
                .append("对比源产品「无向连通分量」基线与本产品「沿 DOWNSTREAM 的下游可达子集」。\n\n");
        sb.append("## 结论\n\n");
        sb.append("- 本产品受影响集合 ⊆ 基线受影响集合：基线把上游一并框进连通分量，")
                .append("本产品只沿 DOWNSTREAM 取可达子集，故恒为子集。\n");
        sb.append("- 存在多报字段数 > 0 的用例：下表「多报」列均大于 0（含改动点本身与上游），")
                .append("证明两种口径不是同一实现换名。\n");
        sb.append("- 保守口径并入 FILTER 边，受影响字段数 ≥ 严格口径；多出的恰为过滤边带来的字段。\n");
        sb.append("- 图规模覆盖 JOIN 扇入、SUM/聚合、CTE 消费与含环图；平均耗时见文末。\n\n");
        sb.append("## 差异统计表（12 张样例图）\n\n");
        sb.append("| 图 | 形态 | 节点 | 边 | 严格受影响 | 保守受影响 | 基线受影响 | 多报 | 命中率 | 含环 | 耗时(ms) | 多报字段清单样本 |\n");
        sb.append("|---|---|---:|---:|---:|---:|---:|---:|---:|:---:|---:|---|\n");
        for (String[] r : rows) {
            sb.append('|');
            for (String c : r) {
                sb.append(' ').append(c).append(" |");
            }
            sb.append('\n');
        }
        sb.append('\n');
        sb.append("说明：命中率 = 保守口径受影响字段数 / 基线受影响字段数；")
                .append("多报 = 基线受影响集合 − 保守口径受影响集合（基线多框入上游与改动点本身）。\n\n");
        sb.append(String.format(Locale.ROOT, "12 张图三种计算的平均合计耗时：%.3f ms（机器相关，仅供回归对照）。%n%n", avgMs));
        sb.append("## 复跑命令\n\n");
        sb.append("```\n");
        sb.append("mvn -q test -Dtest=LineageBaselineBenchmarkTest\n");
        sb.append("mvn -q test -Dtest=ImpactTraceBaselineTest\n");
        sb.append("mvn -q test -Dtest=LineageScaleTest\n");
        sb.append("```\n");
        sb.append("\n复跑会在项目根工作目录下覆盖本文件；如需固定工作目录，")
                .append("请在项目根执行上述命令。\n");
        return sb.toString();
    }
}
