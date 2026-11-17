package com.chaosforge.medins.sqlparse;

import com.chaosforge.medins.model.EdgeKind;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Task 8：CTE / FROM 子查询 / UNION ALL 归因展开与分支列数冲突登记。 */
public class SqlParseCompositeTest {

    @Test
    void cteRegistersVirtualViewNode() throws Exception {
        ParseResult r = SqlParseFixtures.parseSample("v_dept_cost_cte");
        assertTrue(r.success());
        // CTE 内 3 条边挂 COST_BASE
        assertTrue(SqlParseFixtures.hasEdge(r, "item_id", "PART", "P_PARTKEY", EdgeKind.DIRECT));
        assertTrue(SqlParseFixtures.hasEdge(r, "unit_cost", "PARTSUPP", "PS_SUPPLYCOST", EdgeKind.DIRECT));
        assertTrue(SqlParseFixtures.hasEdge(r, "item_name", "PART", "P_NAME", EdgeKind.DIRECT));
        assertTrue(r.edges().stream().anyMatch(e -> "COST_BASE".equals(e.viewName())),
                "应存在以 CTE 名为 viewName 的虚拟视图节点边");
        // 外层消费 CTE 列连边
        assertTrue(SqlParseFixtures.hasEdge(r, "item_id", "COST_BASE", "ITEM_ID", EdgeKind.DIRECT));
        assertTrue(SqlParseFixtures.hasEdge(r, "item_name", "COST_BASE", "ITEM_NAME", EdgeKind.DIRECT));
        // adj_cost 为 DERIVED
        assertTrue(SqlParseFixtures.hasEdge(r, "adj_cost", "COST_BASE", "UNIT_COST", EdgeKind.DERIVED));
    }

    @Test
    void fromSubqueryAliasMerged() throws Exception {
        ParseResult r = SqlParseFixtures.parseSample("v_cost_subquery");
        assertTrue(r.success());
        assertTrue(SqlParseFixtures.hasEdge(r, "bill_no", "LINEITEM", "L_ORDERKEY", EdgeKind.DIRECT));
        assertTrue(SqlParseFixtures.hasEdge(r, "fee_amt", "LINEITEM", "L_EXTENDEDPRICE", EdgeKind.DIRECT));
        assertTrue(SqlParseFixtures.hasEdge(r, "discounted_amt", "LINEITEM", "L_EXTENDEDPRICE", EdgeKind.DERIVED));
        // 别名归并后不应残留子查询别名冒充来源表
        assertFalse(SqlParseFixtures.hasEdge(r, "bill_no", "S", "BILL_NO", EdgeKind.DIRECT));
    }

    @Test
    void unionAllBranchesWithBranchIndex() throws Exception {
        ParseResult r = SqlParseFixtures.parseSample("v_fee_detail_union");
        assertTrue(r.success());
        // 两分支各自边并集
        assertTrue(SqlParseFixtures.hasEdge(r, "bill_no", "LINEITEM", "L_ORDERKEY", EdgeKind.DIRECT));
        assertTrue(SqlParseFixtures.hasEdge(r, "fee_amt", "LINEITEM", "L_EXTENDEDPRICE", EdgeKind.DIRECT));
        assertTrue(SqlParseFixtures.hasEdge(r, "bill_no", "ORDERS", "O_ORDERKEY", EdgeKind.DIRECT));
        assertTrue(SqlParseFixtures.hasEdge(r, "fee_amt", "ORDERS", "O_TOTALPRICE", EdgeKind.DIRECT));
        Set<Integer> branches = new HashSet<>();
        for (ColumnEdge e : r.edges()) branches.add(e.branchIndex());
        assertTrue(branches.contains(0), "需含 branchIndex=0");
        assertTrue(branches.contains(1), "需含 branchIndex=1");
        assertFalse(SqlParseFixtures.hasReason(r, ManualReviewItem.Reason.UNION_BRANCH_CONFLICT));
    }

    @Test
    void unionBranchConflictRegisteredNotMerged() throws Exception {
        ParseResult r = SqlParseFixtures.parseSample("v_fee_detail_union_bad");
        assertTrue(r.success());
        assertEquals(1L, SqlParseFixtures.countReason(r, ManualReviewItem.Reason.UNION_BRANCH_CONFLICT));
        // 两分支边都保留，不得只取其一
        assertTrue(SqlParseFixtures.hasEdge(r, "bill_no", "LINEITEM", "L_ORDERKEY", EdgeKind.DIRECT));
        assertTrue(SqlParseFixtures.hasEdge(r, "fee_amt", "LINEITEM", "L_EXTENDEDPRICE", EdgeKind.DIRECT));
        assertTrue(SqlParseFixtures.hasEdge(r, "bill_no", "ORDERS", "O_ORDERKEY", EdgeKind.DIRECT));
        assertTrue(r.report().degradedSegments() >= 1);
        assertTrue(r.report().degradationReasons().containsKey("union_branch_conflict"));
    }
}
