package com.chaosforge.medins.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 变更单降级导出：院内没有写接口 / 回写仍失败时，把回写请求体原样落盘为带版本号的变更单 JSON，
 * 文件头注明「待人工录入院内台账」，由专员手工录入，绝不把 mock 当最终对接面。
 */
public final class ChangeOrderExporter {

    /** 变更单文件版本号。 */
    public static final String VERSION = "1.0";
    /** 文件头提示语。 */
    public static final String NOTICE = "待人工录入院内台账";

    private ChangeOrderExporter() { }

    public static Path export(WriteBackRequest req, Path dir) throws IOException {
        if (req == null) {
            throw new IllegalArgumentException("变更单导出需要回写请求体");
        }
        Files.createDirectories(dir);
        Map<String, Object> doc = new LinkedHashMap<>();
        // 文件头：版本号 + 人工录入提示，放在最前，便于人工一眼看到
        doc.put("change_order_version", VERSION);
        doc.put("notice", NOTICE);
        // 写入体原样落盘（字段名与 §2.4 一致）
        doc.putAll(req.toMap());

        String json = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(doc);
        Path file = dir.resolve("change-order-" + safeName(req.idempotencyKey()) + ".json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    private static String safeName(String key) {
        String cleaned = (key == null ? "" : key).replaceAll("[^A-Za-z0-9._-]", "_").toLowerCase(Locale.ROOT);
        if (cleaned.length() > 48) {
            cleaned = cleaned.substring(0, 48);
        }
        return cleaned.isEmpty() ? "unknown" : cleaned;
    }
}
