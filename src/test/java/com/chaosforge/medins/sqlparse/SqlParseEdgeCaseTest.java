package com.chaosforge.medins.sqlparse;

import com.chaosforge.medins.model.EdgeKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Task 13：五类边界与异常用例的降级行为固化，断言「讲不清就登记」且不产看似完整的错边。 */
public class SqlParseEdgeCaseTest {

    @Test
    void allFiveDegradeWithExpectedReason() throws Exception {
        String[][] cases = {
                {"select_star", "select_star"},
                {"union_branch_conflict", "union_branch_conflict"},
                {"subquery_unresolved", "subquery_unresolved"},
                {"positional_group_by", "positional_group_by"},
                {"dialect_unsupported", "dialect_unsupported"},
        };
        for (String[] c : cases) {
            ParseResult r = SqlParseFixtures.parse("edge_" + c[0], SqlParseFixtures.edgecase(c[0]));
            assertFalse(r.reviewItems().isEmpty(), c[0] + " 应有待人工核对条目（不得静默跳过）");
            assertTrue(r.reviewItems().stream().anyMatch(i -> i.reason().wireName().equals(c[1])),
                    c[0] + " reason 应为 " + c[1]);
        }
    }

    @Test
    void starNoFakeColumns() throws Exception {
        ParseResult r = SqlParseFixtures.parse("star", SqlParseFixtures.edgecase("select_star"));
        assertEquals(1L, r.edges().stream().filter(ColumnEdge::unresolved).count());
        assertEquals("*", r.edges().get(0).outputColumn());
        assertFalse(SqlParseFixtures.anySourceColumn(r, "C_CUSTKEY"), "不得输出具体列名冒充已解析");
    }

    @Test
    void unionConflictKeepsBothBranches() throws Exception {
        ParseResult r = SqlParseFixtures.parse("ub", SqlParseFixtures.edgecase("union_branch_conflict"));
        assertTrue(SqlParseFixtures.hasEdge(r, "bill_no", "LINEITEM", "L_ORDERKEY", EdgeKind.DIRECT));
        assertTrue(SqlParseFixtures.hasEdge(r, "fee_amt", "LINEITEM", "L_EXTENDEDPRICE", EdgeKind.DIRECT));
        assertTrue(SqlParseFixtures.hasEdge(r, "bill_no", "ORDERS", "O_ORDERKEY", EdgeKind.DIRECT));
        assertTrue(r.report().degradedSegments() >= 1);
    }

    @Test
    void subqueryNoFakeResolvedEdge() throws Exception {
        ParseResult r = SqlParseFixtures.parse("sq", SqlParseFixtures.edgecase("subquery_unresolved"));
        assertTrue(r.edges().stream().anyMatch(ColumnEdge::unresolved), "外层引用应产不可解析标记边");
        assertFalse(SqlParseFixtures.hasEdge(r, "bill_no", "MERGED", "BILL_NO", EdgeKind.DIRECT),
                "不得把子查询别名当成已解析的真实来源表");
    }

    @Test
    void positionalNoLiteralColumn() throws Exception {
        ParseResult r = SqlParseFixtures.parse("pg", SqlParseFixtures.edgecase("positional_group_by"));
        assertFalse(SqlParseFixtures.anyOutputColumn(r, "3"));
        assertFalse(SqlParseFixtures.anySourceColumn(r, "3"));
        assertTrue(SqlParseFixtures.countReason(r, ManualReviewItem.Reason.POSITIONAL_GROUP_BY) >= 1);
    }

    @Test
    void dialectExplicitUnsupportedError() throws Exception {
        ParseResult r = SqlParseFixtures.parse("dl", SqlParseFixtures.edgecase("dialect_unsupported"));
        assertFalse(r.success());
        StructuredError e = r.errors().get(0);
        assertEquals("DIALECT_UNSUPPORTED", e.code());
        assertTrue(e.line() >= 1 && e.column() >= 1);
        assertTrue(e.message().contains("Oracle") || e.message().contains("不支持"));
    }

    @Test
    void noneSilentlySkipped() throws Exception {
        for (String name : new String[]{"select_star", "union_branch_conflict",
                "subquery_unresolved", "positional_group_by"}) {
            ParseResult r = SqlParseFixtures.parse(name, SqlParseFixtures.edgecase(name));
            assertTrue(r.success(), name + " 应为成功但降级");
            assertTrue(r.report().degradedSegments() >= 1, name + " 应计入降级段");
            assertTrue(r.report().reviewItemCount() >= 1, name + " 应有待核对条目");
        }
    }
}
