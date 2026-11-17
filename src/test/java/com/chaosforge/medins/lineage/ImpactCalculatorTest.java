package com.chaosforge.medins.lineage;

import com.chaosforge.medins.model.ChangeKind;
import com.chaosforge.medins.model.EdgeKind;
import com.chaosforge.medins.model.FieldKey;
import com.chaosforge.medins.model.ImpactRecord;
import com.chaosforge.medins.model.ImpactScope;
import com.chaosforge.medins.model.ImpactType;
import com.chaosforge.medins.model.ListField;
import com.chaosforge.medins.sqlparse.SqlDigest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.chaosforge.medins.lineage.LineageFixtures.edge;
import static com.chaosforge.medins.lineage.LineageFixtures.fk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task25：可达集 × 清单对齐 → 影响记录；字段齐全、置信度分档、必填优先、批次占位。 */
class ImpactCalculatorTest {

    private static final FieldKey SEED = fk("VA.A");

    private FieldLineageGraph graph() {
        FieldLineageGraph g = new FieldLineageGraph();
        edge(g, "SRC.C", "VA.A", EdgeKind.DIRECT);
        edge(g, "VA.A", "VB.B", EdgeKind.DIRECT);
        edge(g, "VB.B", "VC.C", EdgeKind.DERIVED);
        edge(g, "VC.C", "VD.D", EdgeKind.AGGREGATE);
        edge(g, "VA.A", "VE.E", EdgeKind.FILTER);   // 保守口径才纳入
        return g;
    }

    private ListField lf(String name, String section, boolean required, String basis) {
        return new ListField(section, name, name + "_CODE", ListField.VALUE_TYPE_NUMBER, required, basis);
    }

    /** 两条必填（C 在基本信息、B 在医疗收费），D 非必填但同在基本信息，用于验证必填优先。 */
    private List<ListField> dict() {
        return List.of(
                lf("B", ListField.SECTION_FEE, true, "收费必填口径"),
                lf("C", ListField.SECTION_BASIC, true, "基本信息口径"),
                lf("D", ListField.SECTION_BASIC, false, "住院口径"),
                lf("E", ListField.SECTION_OUTPATIENT_CHRONIC, true, "门诊慢特病口径"));
    }

    private ImpactRecord bySource(List<ImpactRecord> records, String column) {
        return records.stream().filter(r -> r.sourceColumn().equals(column))
                .findFirst().orElseThrow(() -> new AssertionError("缺少来源列 " + column));
    }

    @Test
    void recordsCarryAllRequiredFields() {
        FieldLineageGraph g = graph();
        ReachabilityResult strict = DownstreamReachability.reachable(g, SEED, ImpactScope.STRICT);
        List<ImpactRecord> records =
                ImpactCalculator.calculate(SEED, strict, dict(), "DIGEST", ChangeKind.RENAMED);

        assertEquals(strict.fields().size(), records.size(), "每条可达字段一条记录");
        for (ImpactRecord r : records) {
            assertTrue(!r.section().isBlank(), "清单分区");
            assertTrue(!r.listFieldName().isBlank(), "清单字段名");
            assertTrue(!r.sourceColumn().isBlank(), "来源列");
            assertNotNull(r.impact());
            assertNotNull(r.verdict());
            assertNotNull(r.scope());
            assertTrue(r.confidence() >= 0.0d && r.confidence() <= 1.0d);
            assertTrue(r.hops() >= 1);
            assertTrue(!r.reasonZh().isBlank());
            assertEquals("VA", r.viewName());
            assertEquals("A", r.changedField());
            assertEquals("DIGEST", r.sqlDigest());
            assertEquals(ChangeKind.RENAMED, r.changeKind());
            assertEquals(SqlDigest.idempotencyKey("VA", "DIGEST", "A"), r.idempotencyKey());
        }
    }

    @Test
    void requiredFieldsComeFirstThenSectionOrder() {
        FieldLineageGraph g = graph();
        ReachabilityResult strict = DownstreamReachability.reachable(g, SEED, ImpactScope.STRICT);
        List<ImpactRecord> records =
                ImpactCalculator.calculate(SEED, strict, dict(), "DIGEST", ChangeKind.RENAMED);
        assertEquals(3, records.size());

        // 必填优先；同必填按 基本信息(0) < 医疗收费(3)
        assertEquals(ListField.SECTION_BASIC, records.get(0).section());
        assertEquals("C", records.get(0).listFieldName());
        assertEquals(ListField.SECTION_FEE, records.get(1).section());
        assertEquals("B", records.get(1).listFieldName());
        // D 虽在基本信息（分区序号 0），但非必填，仍排在两个必填之后
        assertEquals("D", records.get(2).listFieldName());
        assertEquals(ListField.SECTION_BASIC, records.get(2).section());
    }

    @Test
    void confidenceDecaysByHopsAndEdgeKindAndLowConfidenceGoesSuspected() {
        FieldLineageGraph g = graph();
        ReachabilityResult strict = DownstreamReachability.reachable(g, SEED, ImpactScope.STRICT);
        List<ImpactRecord> records =
                ImpactCalculator.calculate(SEED, strict, dict(), "DIGEST", ChangeKind.RENAMED);

        double confB = bySource(records, "B").confidence();
        double confC = bySource(records, "C").confidence();
        double confD = bySource(records, "D").confidence();
        assertEquals(1.0d, confB, 1e-9);           // 一跳直取
        assertTrue(confB > confC && confC > confD, "跳数越远、边类型越弱，置信度越低");

        assertEquals(ImpactType.DIRECT, bySource(records, "B").impact());
        assertEquals(ImpactType.SUSPECTED, bySource(records, "D").impact(),
                "三跳字段需人工确认，不自动判定实质影响");
        assertEquals(3, bySource(records, "D").hops());
    }

    @Test
    void scopeAndConfidenceChangeWithInputAndBasisNoteIsCarried() {
        FieldLineageGraph g = graph();
        ReachabilityResult strict = DownstreamReachability.reachable(g, SEED, ImpactScope.STRICT);
        ReachabilityResult cons = DownstreamReachability.reachable(g, SEED, ImpactScope.CONSERVATIVE);
        List<ImpactRecord> strictRecs =
                ImpactCalculator.calculate(SEED, strict, dict(), "DIGEST", ChangeKind.RENAMED);
        List<ImpactRecord> consRecs =
                ImpactCalculator.calculate(SEED, cons, dict(), "DIGEST2", ChangeKind.LOGIC_CHANGED);

        for (ImpactRecord r : strictRecs) {
            assertEquals(ImpactScope.STRICT, r.scope());
            assertTrue(r.reasonZh().contains("严格口径"));
        }
        ImpactRecord e = bySource(consRecs, "E");
        assertEquals(ImpactScope.CONSERVATIVE, e.scope());
        assertTrue(e.reasonZh().contains("保守口径"));
        assertEquals(ChangeKind.LOGIC_CHANGED, e.changeKind());
        assertEquals("DIGEST2", e.sqlDigest());
        assertEquals(ImpactType.SUSPECTED, e.impact(), "过滤边带来的字段进入疑似波及");
        assertTrue(e.confidence() < bySource(strictRecs, "B").confidence());

        // basis_note 随行写入 reasonZh
        assertTrue(bySource(strictRecs, "B").reasonZh().contains("收费必填口径"));
        assertTrue(bySource(strictRecs, "C").reasonZh().contains("基本信息口径"));
    }

    @Test
    void batchIsAlwaysPlaceholderNoFakeBatchNumber() {
        FieldLineageGraph g = graph();
        ReachabilityResult cons = DownstreamReachability.reachable(g, SEED, ImpactScope.CONSERVATIVE);
        List<ImpactRecord> records =
                ImpactCalculator.calculate(SEED, cons, dict(), "DIGEST", ChangeKind.LOGIC_CHANGED);
        assertEquals("<EXAMPLE_BATCH>", ImpactCalculator.BATCH_PLACEHOLDER);
        for (ImpactRecord r : records) {
            assertEquals("<EXAMPLE_BATCH>", r.batch(), "批次一律用占位符");
            assertFalse(r.batch().matches(".*\\d{6,}.*"),
                    "禁止形似真实批次号的字面量：" + r.batch());
        }
    }
}
