package com.chaosforge.medins.sqlparse;

import com.chaosforge.medins.model.EdgeKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Task 9：GROUP BY 位置引用、别名归一、同名列以限定名消歧、无限定名同名歧义。 */
public class SqlParseAliasTest {

    @Test
    void positionalGroupByResolvesToProjectionColumns() throws Exception {
        ParseResult r = SqlParseFixtures.parseSample("v_group_pos");
        assertTrue(r.success());
        assertTrue(SqlParseFixtures.hasEdge(r, "priority_code", "ORDERS", "O_ORDERPRIORITY", EdgeKind.DIRECT));
        assertTrue(SqlParseFixtures.hasEdge(r, "status_code", "ORDERS", "O_ORDERSTATUS", EdgeKind.DIRECT));
        // 分组键同时产 AGGREGATE（分组依赖）
        assertTrue(SqlParseFixtures.hasEdge(r, "priority_code", "ORDERS", "O_ORDERPRIORITY", EdgeKind.AGGREGATE));
        assertTrue(SqlParseFixtures.hasEdge(r, "status_code", "ORDERS", "O_ORDERSTATUS", EdgeKind.AGGREGATE));
        // 不出现名为 "1"/"2" 的列名
        assertFalse(SqlParseFixtures.anyOutputColumn(r, "1"));
        assertFalse(SqlParseFixtures.anyOutputColumn(r, "2"));
        assertFalse(SqlParseFixtures.anySourceColumn(r, "1"));
        assertFalse(SqlParseFixtures.anySourceColumn(r, "2"));
    }

    @Test
    void aliasNormalizationAndQualifiedDisambiguation() throws Exception {
        ParseResult r = SqlParseFixtures.parseSample("v_patient_basic");
        assertEquals(5L, r.edgesOfKind(EdgeKind.DIRECT).size(), "恰好 5 条 DIRECT");
        // a./b. 限定名区分同名来源
        assertTrue(SqlParseFixtures.hasEdge(r, "nation_key_a", "CUSTOMER", "C_NATIONKEY", EdgeKind.DIRECT));
        assertTrue(SqlParseFixtures.hasEdge(r, "nation_key_b", "NATION", "N_NATIONKEY", EdgeKind.DIRECT));
        assertTrue(r.edges().stream().noneMatch(ColumnEdge::ambiguous), "全限定名场景无歧义");
        assertFalse(SqlParseFixtures.hasReason(r, ManualReviewItem.Reason.AMBIGUOUS_COLUMN));
    }

    @Test
    void unqualifiedColumnInJoinIsAmbiguous() {
        String sql = "SELECT patient_id, nation_name\n"
                + "  FROM customer a JOIN nation b ON a.c_nationkey = b.n_nationkey\n";
        ParseResult r = SqlParseFixtures.parse("v_ambiguous", sql);
        assertTrue(r.success());
        assertTrue(r.edges().stream().anyMatch(ColumnEdge::ambiguous),
                "无限定名列在多表 JOIN 下应标记 ambiguous");
        assertTrue(SqlParseFixtures.countReason(r, ManualReviewItem.Reason.AMBIGUOUS_COLUMN) >= 1,
                "应登记 AMBIGUOUS_COLUMN 待核对条目");
        assertTrue(r.report().degradationReasons().containsKey("ambiguous_column"));
    }

    @Test
    void positionalOutOfRangeDegrades() {
        String sql = "SELECT o.o_orderpriority AS priority_code\n"
                + "  FROM orders o\n GROUP BY 9\n";
        ParseResult r = SqlParseFixtures.parse("v_pos_bad", sql);
        assertTrue(r.success());
        assertTrue(SqlParseFixtures.countReason(r, ManualReviewItem.Reason.POSITIONAL_GROUP_BY) >= 1);
        assertFalse(SqlParseFixtures.anyOutputColumn(r, "9"));
    }
}
