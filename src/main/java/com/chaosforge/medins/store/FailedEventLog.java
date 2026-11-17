package com.chaosforge.medins.store;

import com.chaosforge.medins.config.SecretRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 失败事件落盘与重放定位：影响面计算异常按「每行一个 JSON」的 {@code failed_events.log} 格式落盘。
 *
 * <p>字段：时间 / 视图 / 阶段 / 错误码 / 错误摘要 / 输入摘要 / 幂等键。
 * 落盘失败不抛异常掩盖原始错误，而是退回 stderr 保留事件；支持按幂等键或视图筛选，
 * 并可用同一输入重跑该阶段复现问题（{@link #replay}）。
 */
public final class FailedEventLog {

    public static final String DEFAULT_FILE = "logs/failed_events.log";

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<FailedEvent> events = new ArrayList<>();
    private volatile StageRunner runner;

    public FailedEventLog(Path file) {
        this(file, null);
    }

    public FailedEventLog(Path file, StageRunner runner) {
        this.file = file;
        this.runner = runner;
        ensureParent();
        load();
    }

    public void setRunner(StageRunner runner) {
        this.runner = runner;
    }

    /** 追加一行 JSON；写文件失败退回 stderr 并保留事件，不抛异常掩盖原始错误。 */
    public synchronized void append(FailedEvent e) {
        if (e == null) {
            return;
        }
        events.add(e);
        String line;
        try {
            line = mapper.writeValueAsString(e.toMap());
        } catch (IOException bad) {
            line = "{\"error\":\"序列化失败\"}";
        }
        try {
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException io) {
            System.err.println("[FailedEventLog] 落盘失败，退回 stderr（保留原始异常，不向上抛）："
                    + SecretRedactor.empty().redact(String.valueOf(io.getMessage())));
            System.err.println(line);
        }
    }

    public synchronized List<FailedEvent> byKey(String idempotencyKey) {
        List<FailedEvent> out = new ArrayList<>();
        for (FailedEvent e : events) {
            if (idempotencyKey != null && idempotencyKey.equals(e.idempotencyKey())) {
                out.add(e);
            }
        }
        return List.copyOf(out);
    }

    public synchronized List<FailedEvent> byView(String view) {
        List<FailedEvent> out = new ArrayList<>();
        for (FailedEvent e : events) {
            if (view != null && view.equals(e.view())) {
                out.add(e);
            }
        }
        return List.copyOf(out);
    }

    public synchronized List<FailedEvent> all() {
        return List.copyOf(events);
    }

    /**
     * 用同一输入摘要重跑该阶段，返回「同阶段错误」的复现事件
     * （阶段与错误码与原事件一致，错误摘要来自本次重跑）。
     */
    public FailedEvent replay(FailedEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("重放需要失败事件");
        }
        Throwable reproduced = null;
        StageRunner r = runner;
        if (r != null) {
            try {
                reproduced = r.rerun(event.stage(), event.inputSummary());
            } catch (Throwable t) {
                reproduced = t;
            }
        }
        String summary = reproduced == null
                ? event.errorSummary()
                : (reproduced.getMessage() == null ? reproduced.toString() : reproduced.getMessage());
        return FailedEvent.of(Instant.now().toString(), event.view(), event.stage(),
                event.errorCode(), summary, event.inputSummary(), event.idempotencyKey());
    }

    private void ensureParent() {
        if (file != null && file.getParent() != null) {
            try {
                Files.createDirectories(file.getParent());
            } catch (IOException ignored) {
                // 落盘时再统一处理
            }
        }
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = mapper.readValue(line, Map.class);
                    events.add(FailedEvent.fromMap(m));
                } catch (IOException badLine) {
                    System.err.println("[FailedEventLog] 跳过损坏行：" + badLine.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("[FailedEventLog] 读取失败事件失败：" + e.getMessage());
        }
    }

    /** 阶段重放器：用同一输入重跑该阶段，返回该阶段再次产生的错误（成功返回 null）。 */
    @FunctionalInterface
    public interface StageRunner {
        Throwable rerun(String stage, String inputSummary) throws Exception;
    }

    /** 失败事件：时间 / 视图 / 阶段 / 错误码 / 错误摘要 / 输入摘要 / 幂等键。 */
    public static final class FailedEvent {

        private final String at;
        private final String view;
        private final String stage;
        private final String errorCode;
        private final String errorSummary;
        private final String inputSummary;
        private final String idempotencyKey;

        private FailedEvent(String at, String view, String stage, String errorCode,
                            String errorSummary, String inputSummary, String idempotencyKey) {
            this.at = at == null ? "" : at;
            this.view = view == null ? "" : view;
            this.stage = stage == null ? "" : stage;
            this.errorCode = errorCode == null ? "" : errorCode;
            this.errorSummary = SecretRedactor.empty().redact(errorSummary == null ? "" : errorSummary);
            this.inputSummary = SecretRedactor.empty().redact(inputSummary == null ? "" : inputSummary);
            this.idempotencyKey = idempotencyKey == null ? "" : idempotencyKey;
        }

        public static FailedEvent of(String at, String view, String stage, String errorCode,
                                     String errorSummary, String inputSummary, String idempotencyKey) {
            return new FailedEvent(at, view, stage, errorCode, errorSummary, inputSummary, idempotencyKey);
        }

        public String at() { return at; }
        public String view() { return view; }
        public String stage() { return stage; }
        public String errorCode() { return errorCode; }
        public String errorSummary() { return errorSummary; }
        public String inputSummary() { return inputSummary; }
        public String idempotencyKey() { return idempotencyKey; }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", at);
            m.put("view", view);
            m.put("stage", stage);
            m.put("error_code", errorCode);
            m.put("error_summary", errorSummary);
            m.put("input_summary", inputSummary);
            m.put("idempotency_key", idempotencyKey);
            return m;
        }

        public static FailedEvent fromMap(Map<String, Object> m) {
            return of(str(m.get("at")), str(m.get("view")), str(m.get("stage")),
                    str(m.get("error_code")), str(m.get("error_summary")),
                    str(m.get("input_summary")), str(m.get("idempotency_key")));
        }

        private static String str(Object o) {
            return o == null ? "" : String.valueOf(o);
        }

        @Override
        public String toString() {
            return "FailedEvent{" + stage + "@" + view + " " + errorCode + "}";
        }
    }
}
