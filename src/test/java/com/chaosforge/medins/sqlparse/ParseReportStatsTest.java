package com.chaosforge.medins.sqlparse;

import com.chaosforge.medins.model.EdgeKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Task 11：ParseReport 运行统计与实际段数、边数、待核对条目数逐项一致。 */
public class ParseReportStatsTest {

    @Test
    void aggregateStatsMatchSegments() throws Exception {
        Map<String, String> all = SampleViews.loadAll();
        SqlViewParser parser = new SqlViewParser();
        List<ParseResult> results = parser.parseAll(all);
        assertEquals(12, results.size(), "应解析 12 段且保持顺序");
        assertEquals(SampleViews.VIEW_NAMES.get(0), results.get(0).viewName());
        assertEquals(SampleViews.VIEW_NAMES.get(11), results.get(11).viewName());

        ParseReport rep = parser.aggregate(results);
        assertEquals(12, rep.totalSegments());
        assertEquals(results.stream().filter(ParseResult::success).count(), rep.successSegments());
        assertEquals(results.stream().filter(r -> !r.success()).count(), rep.failedSegments());
        assertEquals(rep.successSegments() + rep.failedSegments(), rep.totalSegments());

        long totalEdges = results.stream().mapToLong(ParseResult::edgeCount).sum();
        assertEquals(totalEdges, rep.edgeCount(), "边计数应与各段之和一致");

        List<ManualReviewItem> pending = parser.pendingReview(results);
        assertEquals(pending.size(), rep.reviewItemCount(), "待核对条目数应一致");

        for (EdgeKind k : EdgeKind.values()) {
            long expected = results.stream().mapToLong(r -> r.edgesOfKind(k).size()).sum();
            assertEquals(expected, (long) rep.countOf(k), k.wireName() + " 计数应一致");
        }
    }

    @Test
    void perSegmentReportConsistent() throws Exception {
        for (String view : SampleViews.VIEW_NAMES) {
            ParseResult r = SqlParseFixtures.parseSample(view);
            assertEquals(r.edgeCount(), r.report().edgeCount(), view + " 单段边数");
            assertEquals(r.reviewItems().size(), r.report().reviewItemCount(), view + " 单段待核对数");
            assertEquals(1, r.report().totalSegments(), view + " 单段总数");
            for (EdgeKind k : EdgeKind.values()) {
                assertEquals(r.edgesOfKind(k).size(), r.report().countOf(k), view + "/" + k.wireName());
            }
        }
    }

    @Test
    void degradedSegmentsTracked() throws Exception {
        ParseResult star = SqlParseFixtures.parseSample("v_settlement_star");
        assertTrue(star.report().degradedSegments() >= 1, "星号段应计入降级");
        assertTrue(star.report().degradationReasons().containsKey("select_star"));
        ParseResult unionBad = SqlParseFixtures.parseSample("v_fee_detail_union_bad");
        assertTrue(unionBad.report().degradationReasons().containsKey("union_branch_conflict"));
    }

    @Test
    void mergeIsAdditive() throws Exception {
        ParseResult a = SqlParseFixtures.parseSample("v_bill_fee");
        ParseResult b = SqlParseFixtures.parseSample("v_bill_fee_agg");
        ParseReport merged = new SqlViewParser().aggregate(List.of(a, b));
        assertEquals(a.report().totalSegments() + b.report().totalSegments(), merged.totalSegments());
        assertEquals(a.edgeCount() + b.edgeCount(), merged.edgeCount());
    }
}
