package com.chaosforge.medins.lineage;

import com.chaosforge.medins.model.EdgeKind;
import com.chaosforge.medins.model.FieldKey;
import com.chaosforge.medins.model.ImpactScope;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.chaosforge.medins.lineage.LineageFixtures.edge;
import static com.chaosforge.medins.lineage.LineageFixtures.fk;
import static com.chaosforge.medins.lineage.LineageFixtures.keys;
import static com.chaosforge.medins.lineage.LineageFixtures.set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Task23：手工追链一致性回归。
 *
 * <p>期望受影响集合、口径、环与跳数结论全部为人工在图上逐跳追出（非算法反推），
 * 与 src/test/resources/baseline/impact-traces.json 的黄金基准逐字对应。
 * 不符时输出「多报 / 漏报」两组差集，便于定位是构图问题还是遍历问题。
 */
class ImpactTraceBaselineTest {

    /** 一条人工基准：边用 {from,to,kind} 三元组描述。 */
    private record Trace(String id, String scope, String seed, List<String[]> edges,
                         Set<String> expected, boolean cycle, int maxHops) { }

    private static final List<Trace> TRACES = List.of(
            new Trace("T1", "strict", "VA.OUT1", List.of(
                    new String[]{"SRCA.C1", "VA.OUT1", "DIRECT"},
                    new String[]{"VA.OUT1", "VB.OUT1", "DIRECT"},
                    new String[]{"VA.OUT1", "VC.OUT1", "DERIVED"},
                    new String[]{"VB.OUT1", "VD.OUT1", "DIRECT"},
                    new String[]{"VC.OUT1", "VD.OUT1", "AGGREGATE"}),
                    set("VB.OUT1", "VC.OUT1", "VD.OUT1"), false, 2),
            new Trace("T2", "conservative", "SRCT.STATUS", List.of(
                    new String[]{"SRCT.STATUS", "VA.STATUS", "DIRECT"},
                    new String[]{"SRCT.STATUS", "VA.FEE_AMT", "FILTER"},
                    new String[]{"VA.FEE_AMT", "VB.FEE_AMT", "DIRECT"}),
                    set("VA.STATUS", "VA.FEE_AMT", "VB.FEE_AMT"), false, 2),
            new Trace("T3", "strict", "VA.X", List.of(
                    new String[]{"VA.X", "VB.X", "DIRECT"},
                    new String[]{"VB.X", "VC.X", "DIRECT"},
                    new String[]{"VC.X", "VA.X", "DIRECT"},
                    new String[]{"VC.X", "VD.X", "AGGREGATE"}),
                    set("VB.X", "VC.X", "VD.X"), true, 3),
            new Trace("T4", "strict", "VA.C", List.of(
                    new String[]{"SRCL.ID", "VA.C", "DIRECT"},
                    new String[]{"VA.C", "VB.C", "DIRECT"},
                    new String[]{"VB.C", "VC.C", "DERIVED"},
                    new String[]{"VC.C", "VD.C", "AGGREGATE"},
                    new String[]{"VD.C", "VE.C", "DIRECT"}),
                    set("VB.C", "VC.C", "VD.C", "VE.C"), false, 4),
            new Trace("T5", "strict", "VA.AMT", List.of(
                    new String[]{"SRC1.AMT", "VA.AMT", "DIRECT"},
                    new String[]{"VA.AMT", "VG.AMT", "DIRECT"},
                    new String[]{"SRC2.AMT", "VB.AMT", "DIRECT"},
                    new String[]{"VB.AMT", "VDELTA.AMT", "DIRECT"}),
                    set("VG.AMT"), false, 1),
            new Trace("T6", "conservative", "ORDERS.O_ORDERSTATUS", List.of(
                    new String[]{"ORDERS.O_CUSTKEY", "VJ.PATIENT_ID", "DIRECT"},
                    new String[]{"LINEITEM.L_PRICE", "VJ.FEE_AMT", "AGGREGATE"},
                    new String[]{"ORDERS.O_ORDERSTATUS", "VJ.FEE_AMT", "FILTER"},
                    new String[]{"VJ.PATIENT_ID", "VSUM.PATIENT_ID", "DIRECT"},
                    new String[]{"VJ.FEE_AMT", "VSUM.TOTAL_FEE", "AGGREGATE"},
                    new String[]{"VSUM.TOTAL_FEE", "VSUM.REPORT_FLAG", "FILTER"}),
                    set("VJ.FEE_AMT", "VSUM.TOTAL_FEE", "VSUM.REPORT_FLAG"), false, 3));

    private FieldLineageGraph buildGraph(Trace t) {
        FieldLineageGraph g = new FieldLineageGraph();
        for (String[] e : t.edges()) {
            edge(g, e[0], e[1], EdgeKind.valueOf(e[2]));
        }
        return g;
    }

    @Test
    void allSixHumanTracesMatchProductExactlyIncludingScope() {
        assertTrue(TRACES.size() >= 6, "至少 6 条人工基准");
        for (Trace t : TRACES) {
            FieldLineageGraph g = buildGraph(t);
            FieldKey seed = fk(t.seed());
            ImpactScope scope = ImpactScope.fromWireName(t.scope());
            assertNotNull(scope, "基准口径须合法：" + t.scope());
            ReachabilityResult r = DownstreamReachability.reachable(g, seed, scope);

            assertEquals(scope, r.scope(), "基准 " + t.id() + " 口径不符也判失败");
            Set<String> actual = keys(r.fields());
            if (!t.expected().equals(actual)) {
                Set<String> extra = new LinkedHashSet<>(actual);   // 多报（产品算出但人工基准没有）
                extra.removeAll(t.expected());
                Set<String> missing = new LinkedHashSet<>(t.expected()); // 漏报（人工基准有但产品没算出）
                missing.removeAll(actual);
                fail("基准 " + t.id() + " 受影响集合不符；多报=" + extra + "；漏报=" + missing);
            }
            assertEquals(t.expected(), actual, "基准 " + t.id() + " 集合须完全一致");
            assertEquals(t.cycle(), r.containsCycle(), "基准 " + t.id() + " 环标记不符");
            assertEquals(t.maxHops(), r.maxHops(), "基准 " + t.id() + " 最大跳数不符");
        }
    }

    @Test
    void baselineJsonFileIsPresentAndConsistentWithHumanAnswers() throws IOException {
        String text;
        // 走测试 classpath 读取，避免依赖运行时工作目录
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("baseline/impact-traces.json")) {
            assertNotNull(in, "人工基准文件应在测试 classpath 上");
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(text.contains("人工复核结论"), "每条基准须含人工复核结论");
        assertTrue(text.contains("人工逐跳"), "每条基准须含链路说明");
        assertTrue(text.contains("不是算法输出反推"), "基准须声明为人工确定");
        assertEquals(TRACES.size(), count(text, "\"id\":"), "JSON 基准条数应与回归用例一致");
        for (Trace t : TRACES) {
            assertTrue(text.contains("\"" + t.seed() + "\""), "基准文件应含 seed " + t.seed());
            assertTrue(text.contains(t.scope()), "基准文件应含期望口径 " + t.scope());
            for (String f : t.expected()) {
                assertTrue(text.contains("\"" + f + "\""),
                        "基准文件应含期望字段 " + f + "（" + t.id() + "）");
            }
        }
    }

    private static int count(String text, String needle) {
        int n = 0;
        int i = 0;
        while ((i = text.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }
}
