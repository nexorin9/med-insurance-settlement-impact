package com.chaosforge.medins.store;

import com.chaosforge.medins.model.ChangeKind;
import com.chaosforge.medins.model.ImpactRecord;
import com.chaosforge.medins.model.ImpactScope;
import com.chaosforge.medins.model.ImpactStatus;
import com.chaosforge.medins.model.ImpactType;
import com.chaosforge.medins.model.Verdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 42：影响面记录持久化（SQLite 单文件）与状态跨重启留存。 */
class ImpactStoreTest {

    @TempDir
    Path dir;

    private Path db() {
        return dir.resolve("impact.db");
    }

    @Test
    void writeRead_upsertCoversWithoutSecondRow() {
        try (ImpactStore store = new ImpactStore(db())) {
            store.upsert(record("K-1", ImpactStatus.COMPUTED, "", "", ""));

            Optional<ImpactRecord> found = store.find("K-1");
            assertTrue(found.isPresent(), "记录可写入可读出");
            ImpactRecord r = found.get();
            assertEquals("V_BILL_FEE", r.viewName());
            assertEquals("FEE_AMT", r.changedField());
            assertEquals("DIGEST", r.sqlDigest());
            assertEquals("基本信息", r.section());
            assertEquals("清单流水号", r.listFieldName());
            assertEquals(ImpactType.DIRECT, r.impact());
            assertEquals(ImpactScope.STRICT, r.scope());
            assertEquals(ChangeKind.LOGIC_CHANGED, r.changeKind());
            assertEquals(Verdict.MATERIALLY_AFFECTED, r.verdict());
            assertEquals(0.9d, r.confidence(), 1e-9);
            assertEquals(2, r.hops());
            assertEquals("<EXAMPLE_BATCH>", r.batch());
            assertEquals(ImpactStatus.COMPUTED, r.status());
            assertEquals(1, store.all().size());

            // 回写成功后按幂等键覆盖既有记录，不追加第二条
            ImpactRecord updated = record("K-1", ImpactStatus.WRITTEN_BACK,
                    "专员A", "2026-11-09T08:50:00Z", "2026-11-09T09:00:00Z");
            store.upsert(updated);
            assertEquals(1, store.all().size(), "同键覆盖不产生第二行");
            ImpactRecord again = store.find("K-1").orElseThrow();
            assertEquals(ImpactStatus.WRITTEN_BACK, again.status());
            assertEquals("专员A", again.confirmedBy());
            assertFalse(again.writeBackAt().isBlank());
        }
    }

    @Test
    void stateSurvivesRestart() {
        try (ImpactStore first = new ImpactStore(db())) {
            first.upsert(record("K-2", ImpactStatus.CONFIRMED,
                    "专员B", "2026-11-09T08:50:00Z", ""));
        }
        // 新进程（新对象）重新打开同一个库文件
        try (ImpactStore reopened = new ImpactStore(db())) {
            assertEquals(1, reopened.all().size(), "重启后记录仍在");
            ImpactRecord r = reopened.find("K-2").orElseThrow();
            assertEquals(ImpactStatus.CONFIRMED, r.status(), "状态跨重启留存");
            assertEquals("专员B", r.confirmedBy());
            assertEquals("2026-11-09T08:50:00Z", r.confirmedAt());
        }
    }

    private static ImpactRecord record(String key, ImpactStatus status,
                                       String confirmedBy, String confirmedAt, String writeBackAt) {
        return ImpactRecord.builder()
                .idempotencyKey(key)
                .viewName("V_BILL_FEE")
                .viewDialect("ORACLE")
                .sqlDigest("DIGEST")
                .changedField("FEE_AMT")
                .changeKind(ChangeKind.LOGIC_CHANGED)
                .section("基本信息")
                .listFieldName("清单流水号")
                .sourceColumn("BILL_NO")
                .impact(ImpactType.DIRECT)
                .confidence(0.9d)
                .scope(ImpactScope.STRICT)
                .hops(2)
                .batch("<EXAMPLE_BATCH>")
                .verdict(Verdict.MATERIALLY_AFFECTED)
                .reasonZh("受影响理由")
                .status(status)
                .confirmedBy(confirmedBy)
                .confirmedAt(confirmedAt)
                .occurredAt("2026-11-09T09:40:00Z")
                .writeBackAt(writeBackAt)
                .build();
    }
}
