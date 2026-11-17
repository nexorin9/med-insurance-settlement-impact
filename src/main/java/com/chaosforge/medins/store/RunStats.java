package com.chaosforge.medins.store;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运行统计：解析成功段数 / 影响面计算次数 / 失败次数 / 回写成功次数。
 *
 * <p>计数可持久化到单个 JSON 文件，跨重启可读；每次自增即落盘，
 * 供顶栏三个大数字与状态条使用。
 */
public final class RunStats {

    private long parseSuccessSegments;
    private long computeCount;
    private long failureCount;
    private long writeBackSuccess;

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    public RunStats() {
        this(null);
    }

    public RunStats(Path file) {
        this.file = file;
        if (file != null) {
            if (file.getParent() != null) {
                try {
                    Files.createDirectories(file.getParent());
                } catch (IOException ignored) {
                    // 落盘时再处理
                }
            }
            load();
        }
    }

    public synchronized void incParseSuccess(int segments) {
        parseSuccessSegments += Math.max(0, segments);
        save();
    }

    public synchronized void incCompute() {
        computeCount++;
        save();
    }

    public synchronized void incFailure() {
        failureCount++;
        save();
    }

    public synchronized void incWriteBackSuccess() {
        writeBackSuccess++;
        save();
    }

    public synchronized long parseSuccessSegments() { return parseSuccessSegments; }
    public synchronized long computeCount() { return computeCount; }
    public synchronized long failureCount() { return failureCount; }
    public synchronized long writeBackSuccess() { return writeBackSuccess; }

    public synchronized Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("parse_success_segments", parseSuccessSegments);
        m.put("compute_count", computeCount);
        m.put("failure_count", failureCount);
        m.put("write_back_success", writeBackSuccess);
        return m;
    }

    public synchronized void save() {
        if (file == null) {
            return;
        }
        try {
            Files.writeString(file, mapper.writeValueAsString(toMap()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[RunStats] 统计落盘失败：" + e.getMessage());
        }
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> m = mapper.readValue(json, Map.class);
            parseSuccessSegments = asLong(m.get("parse_success_segments"));
            computeCount = asLong(m.get("compute_count"));
            failureCount = asLong(m.get("failure_count"));
            writeBackSuccess = asLong(m.get("write_back_success"));
        } catch (IOException e) {
            System.err.println("[RunStats] 读取统计失败：" + e.getMessage());
        }
    }

    private static long asLong(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (RuntimeException e) {
            return 0L;
        }
    }
}
