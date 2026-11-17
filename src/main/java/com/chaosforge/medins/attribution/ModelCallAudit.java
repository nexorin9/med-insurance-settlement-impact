package com.chaosforge.medins.attribution;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 模型调用审计表 —— 每次调用落一条 {@link AuditEntry}，可按视图与批次回查，只增不改。
 *
 * <p>网关为每次 attribute 调用（成功或失败）落一条审计；审计文本在构造条目时已脱敏，
 * 不含凭据字面量与患者明细。
 */
public final class ModelCallAudit {

    private final List<AuditEntry> entries = new ArrayList<>();

    public synchronized void append(AuditEntry e) {
        entries.add(Objects.requireNonNull(e, "审计条目不可为 null"));
    }

    public synchronized List<AuditEntry> byView(String view) {
        List<AuditEntry> out = new ArrayList<>();
        for (AuditEntry e : entries) {
            if (Objects.equals(e.view(), view)) {
                out.add(e);
            }
        }
        return List.copyOf(out);
    }

    public synchronized List<AuditEntry> byBatch(String batch) {
        List<AuditEntry> out = new ArrayList<>();
        for (AuditEntry e : entries) {
            if (Objects.equals(e.batch(), batch)) {
                out.add(e);
            }
        }
        return List.copyOf(out);
    }

    public synchronized List<AuditEntry> all() {
        return List.copyOf(entries);
    }
}
