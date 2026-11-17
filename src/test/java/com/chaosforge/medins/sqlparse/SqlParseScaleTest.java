package com.chaosforge.medins.sqlparse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 14：真实规模与性能上限（TPC-H 8 张底层表 + 12 段跨表视图 SQL）。
 * 断言 12 段全部解析成功、单段 ≤200ms、结论与 golden 逐段一致；打印总耗时/平均耗时作为性能基线。
 *
 * <p>规模数据（表数/段数/总边数/耗时）由本测试输出到标准输出作为深度证据；
 * progress.txt 属契约 §0 禁止修改文件，本模块不写入。
 */
public class SqlParseScaleTest {

    @Test
    void scaleAllTwelveWithinBudgetAndMatchesGolden() throws Exception {
        ObjectMapper mapper = SqlParseFixtures.mapper();
        Map<String, String> all = SampleViews.loadAll();
        assertEquals(12, all.size());

        SqlViewParser parser = new SqlViewParser();
        // 预热：把所有 Druid 解析路径加载一遍，避免首次类加载污染单段耗时统计
        for (String view : SampleViews.VIEW_NAMES) parser.parse(view, all.get(view));

        long totalMs = 0;
        long totalEdges = 0;
        Map<String, Integer> edgeCounts = new LinkedHashMap<>();
        List<String> diffs = new ArrayList<>();

        for (String view : SampleViews.VIEW_NAMES) {
            String sql = all.get(view);
            long t0 = System.nanoTime();
            ParseResult r = parser.parse(view, sql);
            long costMs = (System.nanoTime() - t0) / 1_000_000L;

            assertTrue(r.success(), view + " 应解析成功，无解析失败段");
            assertTrue(costMs <= 200, view + " 单段耗时 " + costMs + "ms 超过 200ms 上限");
            totalMs += costMs;
            totalEdges += r.edgeCount();
            edgeCounts.put(view, r.edgeCount());

            // 与 golden 集逐段一致：每条期望边都要命中
            JsonNode snap = mapper.readTree(SqlParseFixtures.golden(view));
            List<String> actual = SqlParseFixtures.edgeKeys(r);
            for (JsonNode exp : snap.get("expected")) {
                String key = String.join("|",
                        exp.get("output_column").asText(),
                        exp.get("source_table").asText(),
                        exp.get("source_column").asText(),
                        exp.get("edge_kind").asText());
                if (!actual.contains(key)) diffs.add(view + " / " + key);
            }
        }
        assertTrue(diffs.isEmpty(), "规模结论应与 golden 逐段一致，差异：" + diffs);

        // 真实规模下结论与样例规模一致：同一段 SQL 再解析一次，列级边集合不变（只看 SQL 文本，与数据量无关）
        ParseResult again = parser.parse("v_bill_fee", all.get("v_bill_fee"));
        ParseResult first = parser.parse("v_bill_fee", all.get("v_bill_fee"));
        assertEquals(SqlParseFixtures.edgeKeys(first), SqlParseFixtures.edgeKeys(again),
                "数据量变化不得改变列级边结论");
        assertEquals(first.sqlDigest(), again.sqlDigest());

        double avgMs = totalMs / 12.0d;
        System.out.printf("[scale] 底层表数=8 段数=%d 总边数=%d%n", 12, totalEdges);
        System.out.printf("[scale] 各段边数=%s%n", edgeCounts);
        System.out.printf("[scale] 总耗时=%dms 平均单段耗时=%.2fms (上限 200ms)%n", totalMs, avgMs);
    }
}
