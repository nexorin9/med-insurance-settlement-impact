package com.chaosforge.medins.sqlparse;

import com.chaosforge.medins.model.EdgeKind;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Task 5：SUM/COUNT/MAX/MIN/AVG、count(distinct)、分组键并存、窗口分区与排序列不误报。 */
public class SqlParseAggregateTest {

    @Test
    void aggregateFunctionsInSample() throws Exception {
        ParseResult r = SqlParseFixtures.parseSample("v_bill_fee_agg");
        assertTrue(r.success());
        Set<String> funcs = new HashSet<>();
        for (ColumnEdge e : r.edgesOfKind(EdgeKind.AGGREGATE)) funcs.add(e.aggregateFunction());
        assertTrue(funcs.contains("SUM"), "SUM");
        assertTrue(funcs.contains("COUNT"), "COUNT");
        assertTrue(funcs.contains("MAX"), "MAX");
        assertTrue(funcs.contains("AVG"), "AVG");
        // 4 个函数聚合边（GROUP_BY 分组依赖边不计入函数个数）
        assertEquals(4L, r.edgesOfKind(EdgeKind.AGGREGATE).stream()
                .filter(e -> !"GROUP_BY".equals(e.aggregateFunction())).count());
    }

    @Test
    void groupKeyHasBothDirectAndAggregate() throws Exception {
        ParseResult r = SqlParseFixtures.parseSample("v_bill_fee_agg");
        assertTrue(SqlParseFixtures.hasEdge(r, "patient_id", "ORDERS", "O_CUSTKEY", EdgeKind.DIRECT));
        assertTrue(SqlParseFixtures.hasEdge(r, "patient_id", "ORDERS", "O_CUSTKEY", EdgeKind.AGGREGATE));
    }

    @Test
    void minAndCountDistinctMetadata() {
        String sql = "SELECT MIN(l.l_tax) AS min_tax, COUNT(DISTINCT o.o_orderkey) AS bill_cnt\n"
                + "  FROM orders o JOIN lineitem l ON o.o_orderkey = l.l_orderkey\n";
        ParseResult r = SqlParseFixtures.parse("v_agg_extra", sql);
        assertTrue(r.success());
        assertTrue(r.edgesOfKind(EdgeKind.AGGREGATE).stream()
                .anyMatch(e -> "MIN".equals(e.aggregateFunction())));
        ColumnEdge cd = r.edgesOfKind(EdgeKind.AGGREGATE).stream()
                .filter(e -> "bill_cnt".equals(e.outputColumn())).findFirst().orElseThrow();
        assertEquals("COUNT", cd.aggregateFunction());
        assertTrue(cd.distinct(), "count(distinct x) 应记录 distinct=true");
    }

    @Test
    void windowAggregateAndNoOrderColumnLeak() throws Exception {
        ParseResult r = SqlParseFixtures.parseSample("v_bill_fee_window");
        assertTrue(r.success());
        Set<String> funcs = new HashSet<>();
        for (ColumnEdge e : r.edgesOfKind(EdgeKind.AGGREGATE)) funcs.add(e.aggregateFunction());
        assertTrue(funcs.contains("ROW_NUMBER"), "ROW_NUMBER 窗口结果列应为 AGGREGATE");
        assertTrue(funcs.contains("SUM"), "SUM OVER 窗口结果列应为 AGGREGATE");
        // PARTITION BY 分区键归 FILTER
        assertTrue(r.edgesOfKind(EdgeKind.FILTER).stream()
                .anyMatch(e -> "O_CUSTKEY".equals(e.sourceColumn())
                        && "partition-by".equals(e.conditionPosition())));
        // 窗口 ORDER BY 排序列不得误报为来源列
        assertFalse(SqlParseFixtures.anySourceColumn(r, "L_SHIPDATE"),
                "窗口排序列 L_SHIPDATE 不得作为来源列");
        assertFalse(SqlParseFixtures.anyOutputColumn(r, "L_SHIPDATE"));
    }
}
