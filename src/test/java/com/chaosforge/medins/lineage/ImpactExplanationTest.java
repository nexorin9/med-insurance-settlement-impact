package com.chaosforge.medins.lineage;

import com.chaosforge.medins.model.EdgeKind;
import com.chaosforge.medins.model.FieldKey;
import com.chaosforge.medins.model.ImpactScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.chaosforge.medins.lineage.LineageFixtures.edge;
import static com.chaosforge.medins.lineage.LineageFixtures.fk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task26：证据链可回溯到图中边、中文短句含路径与跳数、结构化与文本同源一致。 */
class ImpactExplanationTest {

    private static final FieldKey SEED = fk("VA.C");

    private FieldLineageGraph graph() {
        FieldLineageGraph g = new FieldLineageGraph();
        edge(g, "VA.C", "VB.C", EdgeKind.DIRECT);
        edge(g, "VB.C", "VC.C", EdgeKind.DERIVED);
        edge(g, "VC.C", "VD.C", EdgeKind.AGGREGATE);
        edge(g, "VA.C", "VB2.C", EdgeKind.DIRECT);
        return g;
    }

    @Test
    void everyHopOfEvidenceIsTraceableToAGraphEdge() {
        FieldLineageGraph g = graph();
        ReachabilityResult reach = DownstreamReachability.reachable(g, SEED, ImpactScope.STRICT);
        FieldKey target = fk("VD.C");
        Evidence ev = ImpactExplanation.evidence(g, reach, target);

        assertEquals(3, ev.hops());
        assertEquals(3, ev.path().size());
        assertFalse(ev.path().isEmpty());
        for (PathStep step : ev.path()) {
            assertTrue(g.downstream(step.from()).contains(step.to()),
                    "证据链每跳 from->to 必须在图中存在：" + step);
            assertTrue(g.kindsOf(step.from(), step.to()).contains(step.kind()),
                    "证据链每跳 edge_kind 必须能在该边上找到：" + step);
        }
        assertEquals(SEED, ev.path().get(0).from());
        assertEquals(target, ev.path().get(ev.path().size() - 1).to());
    }

    @Test
    void chineseSentenceContainsPathAndHops() {
        FieldLineageGraph g = graph();
        ReachabilityResult reach = DownstreamReachability.reachable(g, SEED, ImpactScope.STRICT);
        Evidence ev = ImpactExplanation.evidence(g, reach, fk("VD.C"));
        String zh = ev.zh();

        assertTrue(zh.contains("3 跳"), "中文短句应含跳数");
        assertTrue(zh.contains(SEED.key()), "中文短句应含改动点");
        assertTrue(zh.contains("VD.C"), "中文短句应含目标字段");
        for (PathStep step : ev.path()) {
            assertTrue(zh.contains(step.from().key()), "短句应含路径起点 " + step.from().key());
            assertTrue(zh.contains(step.to().key()), "短句应含路径终点 " + step.to().key());
            assertTrue(zh.contains(step.kind().wireName()), "短句应含每跳 edge_kind " + step.kind());
        }
        assertTrue(zh.contains("严格口径"), "短句应含口径");
    }

    @Test
    void structuredMapAndChineseSentenceComeFromSameData() {
        FieldLineageGraph g = graph();
        ReachabilityResult reach = DownstreamReachability.reachable(g, SEED, ImpactScope.STRICT);
        Evidence ev = ImpactExplanation.evidence(g, reach, fk("VD.C"));
        Map<String, Object> m = ev.toMap();

        assertEquals(Integer.valueOf(ev.hops()), m.get("hops"));
        assertEquals(ev.scope().wireName(), m.get("scope"));
        assertEquals(Double.valueOf(ev.confidence()), m.get("confidence"));
        assertEquals(ev.zh(), m.get("zh"), "结构化与文本呈现由同一结构生成");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) m.get("path");
        assertEquals(ev.path().size(), steps.size(), "结构化路径逐跳数与 hops 一致");
        for (int i = 0; i < steps.size(); i++) {
            PathStep step = ev.path().get(i);
            assertEquals(step.from().key(), steps.get(i).get("from"));
            assertEquals(step.to().key(), steps.get(i).get("to"));
            assertEquals(step.kind().wireName(), steps.get(i).get("edge_kind"));
        }
        assertTrue(ev.confidence() > 0.0d);
        assertTrue(((String) m.get("confidence_source")).contains("EdgeConfidencePolicy"));
    }

    @Test
    void evidenceByFieldCoversWholeReachableSet() {
        FieldLineageGraph g = graph();
        ReachabilityResult reach = DownstreamReachability.reachable(g, SEED, ImpactScope.STRICT);
        Map<String, Evidence> byField = ImpactExplanation.evidenceByField(g, reach);

        assertEquals(reach.fields().size(), byField.size());
        assertTrue(byField.containsKey("VD.C"));
        for (Evidence ev : byField.values()) {
            for (PathStep step : ev.path()) {
                assertTrue(g.kindsOf(step.from(), step.to()).contains(step.kind()),
                        "任一证据链的每跳都可回溯到边：" + step);
            }
            assertEquals(reach.scope(), ev.scope());
        }
    }
}
