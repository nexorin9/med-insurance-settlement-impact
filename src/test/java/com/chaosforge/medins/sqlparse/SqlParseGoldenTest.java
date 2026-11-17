package com.chaosforge.medins.sqlparse;

import com.chaosforge.medins.model.EdgeKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 12：12 段样例视图 SQL 的 golden 回归集。
 * 采用「期望项 ⊆ 实际边集合」逐条比对，差异按「视图 / 列 / 边类型」列出，断言 12/12 覆盖且差异为空。
 */
public class SqlParseGoldenTest {

    @Test
    void goldenCoversTwelveAndNoDiff() throws Exception {
        ObjectMapper mapper = SqlParseFixtures.mapper();
        List<String> allDiffs = new ArrayList<>();
        int covered = 0;

        for (String view : SampleViews.VIEW_NAMES) {
            JsonNode snap = mapper.readTree(SqlParseFixtures.golden(view));
            assertEquals(view, snap.get("view").asText(), "快照 view 字段应与文件名一致");
            ParseResult r = SqlParseFixtures.parseSample(view);
            assertTrue(r.success(), view + " 应解析成功");

            List<String> actual = SqlParseFixtures.edgeKeys(r);
            List<String> diffs = new ArrayList<>();
            for (JsonNode exp : snap.get("expected")) {
                String key = String.join("|",
                        exp.get("output_column").asText(),
                        exp.get("source_table").asText(),
                        exp.get("source_column").asText(),
                        exp.get("edge_kind").asText());
                if (!actual.contains(key)) diffs.add(view + " / " + key);
            }
            for (JsonNode rr : snap.get("required_review_reasons")) {
                String reason = rr.asText();
                boolean found = r.reviewItems().stream()
                        .anyMatch(i -> i.reason().wireName().equals(reason));
                if (!found) diffs.add(view + " / reason:" + reason);
            }

            // 打印各段四类边命中数与差异条数，作为回归覆盖率的可读指标
            System.out.printf("[golden] %-24s DIRECT=%d DERIVED=%d AGGREGATE=%d FILTER=%d diff=%d%n",
                    view,
                    r.edgesOfKind(EdgeKind.DIRECT).size(),
                    r.edgesOfKind(EdgeKind.DERIVED).size(),
                    r.edgesOfKind(EdgeKind.AGGREGATE).size(),
                    r.edgesOfKind(EdgeKind.FILTER).size(),
                    diffs.size());

            if (diffs.isEmpty()) covered++;
            allDiffs.addAll(diffs);
        }

        assertEquals(12, covered, "应 12/12 段全部覆盖且逐段差异为空");
        assertEquals(0, allDiffs.size(),
                "golden 比对差异应为空，差异项：\n" + String.join("\n", allDiffs));
    }

    @Test
    void allGoldenFilesLoadable() throws Exception {
        ObjectMapper mapper = SqlParseFixtures.mapper();
        for (String view : SampleViews.VIEW_NAMES) {
            JsonNode snap = mapper.readTree(SqlParseFixtures.golden(view));
            assertTrue(snap.has("expected") && snap.get("expected").isArray(), view + " 需含 expected 数组");
            assertFalse(snap.get("expected").isEmpty(), view + " 期望项不可为空");
            assertTrue(snap.has("required_review_reasons"), view + " 需含 required_review_reasons");
        }
    }
}
