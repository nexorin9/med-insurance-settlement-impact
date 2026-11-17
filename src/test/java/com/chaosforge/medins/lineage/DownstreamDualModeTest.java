package com.chaosforge.medins.lineage;

import com.chaosforge.medins.model.ChangeKind;
import com.chaosforge.medins.model.EdgeKind;
import com.chaosforge.medins.model.FieldKey;
import com.chaosforge.medins.model.ImpactRecord;
import com.chaosforge.medins.model.ImpactScope;
import com.chaosforge.medins.model.ListField;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.chaosforge.medins.lineage.LineageFixtures.edge;
import static com.chaosforge.medins.lineage.LineageFixtures.fk;
import static com.chaosforge.medins.lineage.LineageFixtures.keys;
import static com.chaosforge.medins.lineage.LineageFixtures.set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task19：保守口径并入过滤边、双口径差异、口径标签在记录/CSV/说明三处一致。 */
class DownstreamDualModeTest {

    private static final FieldKey SEED = fk("VA.S");

    private FieldLineageGraph graph() {
        FieldLineageGraph g = new FieldLineageGraph();
        edge(g, "SRC.S", "VA.S", EdgeKind.DIRECT);     // 上游
        edge(g, "VA.S", "VB.D", EdgeKind.DIRECT);
        edge(g, "VB.D", "VC.D", EdgeKind.DERIVED);
        edge(g, "VA.S", "VF.F", EdgeKind.FILTER);      // 纯过滤边
        edge(g, "VF.F", "VG.G", EdgeKind.DIRECT);
        return g;
    }

    private ListField lf(String name, String section, boolean required) {
        return new ListField(section, name, name + "_CODE", ListField.VALUE_TYPE_NUMBER,
                required, "口径注释-" + name);
    }

    private List<ListField> dict() {
        return List.of(
                lf("D", ListField.SECTION_FEE, true),
                lf("F", ListField.SECTION_BASIC, false),
                lf("G", ListField.SECTION_FEE, false));
    }

    /** 本地拼一份 CSV 口径列（不依赖别的代理包），用于验证口径标签落到导出产物。 */
    private String csvText(List<ImpactRecord> records) {
        StringBuilder sb = new StringBuilder("分区,字段,来源列,影响类型,置信度,口径,跳数,批次,判定,理由\n");
        for (ImpactRecord r : records) {
            sb.append(r.section()).append(',').append(r.listFieldName()).append(',')
                    .append(r.sourceColumn()).append(',').append(r.impact().wireName()).append(',')
                    .append(r.confidence()).append(',').append(r.scope().wireName()).append(',')
                    .append(r.hops()).append(',').append(r.batch()).append(',')
                    .append(r.verdict().wireName()).append(',').append(r.reasonZh()).append('\n');
        }
        return sb.toString();
    }

    @Test
    void strictVsConservativeDiffEqualsFilterDrivenFields() {
        FieldLineageGraph g = graph();
        ReachabilityResult strict = DownstreamReachability.reachable(g, SEED, ImpactScope.STRICT);
        ReachabilityResult cons = DownstreamReachability.reachable(g, SEED, ImpactScope.CONSERVATIVE);

        assertEquals(set("VB.D", "VC.D"), keys(strict.fields()));
        assertEquals(set("VB.D", "VF.F", "VC.D", "VG.G"), keys(cons.fields()));
        assertTrue(cons.fields().containsAll(strict.fields()), "保守口径应覆盖严格口径");

        Set<FieldKey> extra = DualModeDiff.extraConservativeFields(g, SEED);
        assertEquals(set("VF.F", "VG.G"), keys(extra), "多出集合恰为过滤边带来的字段");
        for (FieldKey f : extra) {
            assertTrue(cons.paths().get(f).stream()
                            .anyMatch(s -> s.kind() == EdgeKind.FILTER),
                    "多出字段的最短路径必须含 FILTER 跳：" + f.key());
        }
    }

    @Test
    void scopeLabelConsistentAcrossRecordCsvAndExplanation() {
        FieldLineageGraph g = graph();
        ReachabilityResult strict = DownstreamReachability.reachable(g, SEED, ImpactScope.STRICT);
        ReachabilityResult cons = DownstreamReachability.reachable(g, SEED, ImpactScope.CONSERVATIVE);
        List<ImpactRecord> strictRecs =
                ImpactCalculator.calculate(SEED, strict, dict(), "DIGEST", ChangeKind.RENAMED);
        List<ImpactRecord> consRecs =
                ImpactCalculator.calculate(SEED, cons, dict(), "DIGEST", ChangeKind.RENAMED);

        // ① ImpactRecord（对象层）：scope 字段与 reasonZh 都带口径
        for (ImpactRecord r : strictRecs) {
            assertEquals(ImpactScope.STRICT, r.scope());
            assertTrue(r.reasonZh().contains(ImpactScope.STRICT.labelZh()));
            assertTrue(r.reasonZh().contains(ImpactScope.STRICT.wireName()));
        }
        for (ImpactRecord r : consRecs) {
            assertEquals(ImpactScope.CONSERVATIVE, r.scope());
            assertTrue(r.reasonZh().contains(ImpactScope.CONSERVATIVE.labelZh()));
            assertTrue(r.reasonZh().contains(ImpactScope.CONSERVATIVE.wireName()));
        }

        // ② CSV（本地生成的口径列）：保守口径记录只出现 conservative
        String csv = csvText(consRecs);
        assertTrue(csv.contains("口径"), "CSV 应含口径列");
        assertTrue(csv.contains(",conservative,"), "CSV 口径列应带保守口径标签");
        assertFalse(csv.contains(",strict,"), "保守口径 CSV 不得混出 strict 口径");

        // ③ 说明文本（证据链中文短句）：与①②同源同口径
        FieldKey target = fk("VG.G");
        Evidence ev = ImpactExplanation.evidence(g, cons, target);
        assertEquals(ImpactScope.CONSERVATIVE, ev.scope());
        assertTrue(ev.zh().contains("保守口径"));
        assertTrue(ev.zh().contains("conservative"));

        ImpactRecord gRec = consRecs.stream()
                .filter(r -> r.sourceColumn().equals("G")).findFirst().orElseThrow();
        assertEquals(ImpactScope.CONSERVATIVE, gRec.scope());
        assertTrue(gRec.reasonZh().contains("conservative"));
        // 三处（记录对象 / CSV / 说明）口径 wireName 完全一致
        assertEquals(gRec.scope().wireName(), ev.scope().wireName());
        assertTrue(csv.contains(gRec.scope().wireName()));
    }
}
