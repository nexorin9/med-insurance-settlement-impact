package com.chaosforge.medins.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 44：失败事件落盘与重放定位（每行一个 JSON、按幂等键/视图筛选、replay 复现同阶段错误）。 */
class FailedEventLogTest {

    @TempDir
    Path dir;

    private static FailedEventLog.FailedEvent event(String key, String view, String stage, String code) {
        return FailedEventLog.FailedEvent.of(
                "2026-11-09T08:50:00Z", view, stage, code,
                "阶段失败摘要", "输入摘要:" + key, key);
    }

    @Test
    void eachLineIsJson_andFiltersByKeyAndView() throws Exception {
        Path file = dir.resolve("failed_events.log");
        FailedEventLog log = new FailedEventLog(file);
        log.append(event("K-1", "V1", "SQL 解析", "PARSE_FAILURE"));
        log.append(event("K-1", "V2", "图构建", "GRAPH_ERROR"));
        log.append(event("K-2", "V1", "归因", "MODEL_UNAVAILABLE"));

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(3, lines.size(), "三个事件三行");
        ObjectMapper mapper = new ObjectMapper();
        for (String line : lines) {
            JsonNode node = mapper.readTree(line);
            assertTrue(node.isObject(), "每行必须是独立 JSON 对象");
            assertTrue(node.has("at") && node.has("view") && node.has("stage")
                    && node.has("error_code") && node.has("error_summary")
                    && node.has("input_summary") && node.has("idempotency_key"));
        }

        assertEquals(2, log.byKey("K-1").size(), "按幂等键筛选命中");
        assertEquals(1, log.byKey("K-2").size());
        assertEquals(2, log.byView("V1").size(), "按视图筛选命中");
        assertEquals(3, log.all().size());
    }

    @Test
    void replayReturnsSameStageError() {
        Path file = dir.resolve("failed_events.log");
        FailedEventLog log = new FailedEventLog(file,
                (stage, input) -> new IllegalStateException("重放复现：" + stage + " / " + input));
        FailedEventLog.FailedEvent original = event("K-1", "V1", "清单对齐", "DICT_UNAVAILABLE");
        log.append(original);

        FailedEventLog.FailedEvent replayed = log.replay(original);
        assertEquals("清单对齐", replayed.stage(), "重放返回同阶段错误");
        assertEquals("DICT_UNAVAILABLE", replayed.errorCode(), "错误码一致");
        assertTrue(replayed.errorSummary().contains("重放复现"), "确实用同一输入重跑了该阶段");
        assertTrue(replayed.errorSummary().contains("清单对齐"));
        assertTrue(replayed.inputSummary().equals(original.inputSummary()), "同一输入摘要");
    }

    @Test
    void writeFailureFallsBackToStderr_noThrow() throws Exception {
        // 把日志「文件」指向一个目录，写入必然失败；append 不得抛异常掩盖原始错误
        Path notAFile = dir.resolve("blocked");
        Files.createDirectory(notAFile);
        FailedEventLog log = new FailedEventLog(notAFile);
        assertDoesNotThrow(() -> log.append(event("K-1", "V1", "SQL 解析", "PARSE_FAILURE")));
        // 事件仍在内存中可查，不因落盘失败而丢失
        assertEquals(1, log.all().size());
    }
}
