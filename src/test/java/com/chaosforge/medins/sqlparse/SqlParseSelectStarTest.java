package com.chaosforge.medins.sqlparse;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Task 7：SELECT * 不展开 + 待人工核对清单机制（条目数与未解析列数一致）。 */
public class SqlParseSelectStarTest {

    @Test
    void sampleStarProducesUnresolvedEdgeAndReview() throws Exception {
        ParseResult r = SqlParseFixtures.parseSample("v_settlement_star");
        assertTrue(r.success(), "星号段是降级成功，不应整段解析失败");
        List<ColumnEdge> unresolved = r.edges().stream().filter(ColumnEdge::unresolved).toList();
        assertEquals(1L, unresolved.size(), "SELECT * 应产 1 条不可解析标记边");
        ColumnEdge e = unresolved.get(0);
        assertEquals("*", e.outputColumn());
        assertEquals("", e.sourceTable());
        assertEquals("", e.sourceColumn());

        long starReviews = SqlParseFixtures.countReason(r, ManualReviewItem.Reason.SELECT_STAR);
        assertEquals(1L, starReviews, "应登记 1 条 SELECT_STAR 待核对条目");
        // 对账：条目数与未解析列数一致
        assertEquals(unresolved.size(), starReviews);
        // 不得输出具体列名冒充已解析
        assertFalse(SqlParseFixtures.anyOutputColumn(r, "bill_no"));
        assertFalse(SqlParseFixtures.anySourceColumn(r, "L_ORDERKEY"));
        // 降级统计可见
        assertTrue(r.report().degradedSegments() >= 1);
    }

    @Test
    void edgecaseStarSameDegradation() throws Exception {
        ParseResult r = SqlParseFixtures.parse("star_edge", SqlParseFixtures.edgecase("select_star"));
        assertTrue(r.success());
        assertEquals(1L, r.edges().stream().filter(ColumnEdge::unresolved).count());
        assertEquals(1L, SqlParseFixtures.countReason(r, ManualReviewItem.Reason.SELECT_STAR));
    }

    @Test
    void normalSegmentHasNoExtraReviewItems() throws Exception {
        ParseResult r = SqlParseFixtures.parseSample("v_bill_fee");
        assertTrue(r.reviewItems().isEmpty(), "普通段不应产生多余待核对条目");
        assertEquals(0, r.report().degradedSegments());
    }

    @Test
    void pendingReviewAggregatesStarItem() throws Exception {
        SqlViewParser parser = new SqlViewParser();
        ParseResult star = parser.parse("v_settlement_star", SqlParseFixtures.sample("v_settlement_star"));
        ParseResult normal = parser.parse("v_bill_fee", SqlParseFixtures.sample("v_bill_fee"));
        List<ManualReviewItem> pending = parser.pendingReview(List.of(star, normal));
        assertEquals(1L, pending.size());
        assertEquals(ManualReviewItem.Reason.SELECT_STAR, pending.get(0).reason());
        assertFalse(pending.get(0).position().isBlank(), "条目需带位置");
        assertFalse(pending.get(0).hint().isBlank(), "条目需带人工确认提示");
    }
}
