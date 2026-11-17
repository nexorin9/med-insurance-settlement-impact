package com.chaosforge.medins.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 43：不可变审计链与撤回留痕（按视图 / 批次回查）。 */
class AuditChainTest {

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("audit.log");
    }

    private static AuditLog.AuditEntry entry(String view, String batch, String operator,
                                             String action, boolean withdrawn, String detail) {
        return AuditLog.AuditEntry.builder()
                .at("2026-11-09T08:50:00Z")
                .operator(operator)
                .action(action)
                .view(view)
                .batch(batch)
                .column("FEE_AMT")
                .section("基本信息")
                .listField("清单流水号")
                .scope("strict")
                .confirmedBy("专员A")
                .withdrawn(withdrawn)
                .idempotencyKey("K-1")
                .fromStatus("PENDING_CONFIRM")
                .toStatus("CONFIRMED")
                .detail(detail)
                .build();
    }

    @Test
    void appendOnly_withdrawLeavesTrace_byViewAndByBatchHit() {
        AuditLog log = new AuditLog(file());
        log.append(entry("V1", "<EXAMPLE_BATCH>", "op1", "状态跃迁", false, "确认记录一"));
        log.append(entry("V1", "<EXAMPLE_BATCH>", "op1", "状态跃迁", false, "确认记录二"));
        assertEquals(2, log.all().size());

        // 撤回只追加一条撤回条目，不物理删除原条目
        log.append(entry("V1", "<EXAMPLE_BATCH>", "op2", "撤回", true, "人工撤回"));
        assertEquals(3, log.all().size(), "条目只增不改");
        assertFalse(log.all().get(0).withdrawn(), "原条目仍在且未被改写");
        assertFalse(log.all().get(1).withdrawn());
        assertTrue(log.all().stream().anyMatch(e -> "撤回".equals(e.action()) && e.withdrawn()),
                "撤回条目留痕");

        assertEquals(3, log.byView("V1").size(), "按视图回查命中");
        assertEquals(3, log.byBatch("<EXAMPLE_BATCH>").size(), "按批次回查命中");
        assertEquals(2, log.byOperator("op1").size(), "按操作人回查命中");
        assertEquals(1, log.byOperator("op2").size());

        // 重新打开后追加式内容仍在
        AuditLog reopened = new AuditLog(file());
        assertEquals(3, reopened.all().size(), "审计链跨实例留存");
    }

    @Test
    void auditTextIsRedacted() {
        AuditLog log = new AuditLog(file());
        log.append(entry("V1", "<EXAMPLE_BATCH>", "op1", "状态跃迁", false,
                "Authorization: Bearer TOKEN123 口令 password=SECRET99"));
        String detail = log.all().get(0).detail();
        assertFalse(detail.contains("TOKEN123"), "审计文本不得出现令牌字面量");
        assertFalse(detail.contains("SECRET99"), "审计文本不得出现口令字面量");
        assertTrue(detail.contains("***"), "凭据应被替换为掩码");
    }
}
