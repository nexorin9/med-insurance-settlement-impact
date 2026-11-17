package com.chaosforge.medins.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 47：定时重算、变更回调提示与运行统计计数（服务层无 Swing 依赖）。 */
class RescanSchedulerTest {

    @TempDir
    Path dir;

    @Test
    void digestChangeTriggersRescan_unchangedDoesNot_andRecordsBeforeAfterDigest() {
        List<String[]> notifications = new ArrayList<>();
        RescanScheduler scheduler = new RescanScheduler(
                (view, payload) -> notifications.add(new String[]{view, payload[0], payload[1]}),
                dir.resolve("stats.json"));

        assertFalse(scheduler.checkAndRescan("V1", "AAA", "AAA"), "摘要未变化不触发");
        assertEquals(0, notifications.size());

        assertTrue(scheduler.checkAndRescan("V1", "AAA", "BBB"), "摘要变化触发重算");
        assertEquals(1, notifications.size());
        assertEquals("V1", notifications.get(0)[0]);
        assertTrue(notifications.get(0)[1].contains("AAA"));
        assertTrue(notifications.get(0)[2].contains("BBB"));

        assertFalse(scheduler.checkAndRescan("V1", "BBB", "BBB"));
        assertEquals(1, notifications.size(), "未变化仍不触发");

        assertEquals(1, scheduler.changeLog().size(), "记录变化前后摘要");
        assertEquals("AAA", scheduler.changeLog().get(0)[1]);
        assertEquals("BBB", scheduler.changeLog().get(0)[2]);
        assertTrue(scheduler.stats().computeCount() >= 1, "影响面计算次数累加");
        assertTrue(scheduler.stats().parseSuccessSegments() >= 1, "解析成功段数累加");
    }

    @Test
    void statsCountersAccumulateAndSurviveRestart() {
        Path statsFile = dir.resolve("stats.json");
        RescanScheduler first = new RescanScheduler(null, statsFile);
        first.stats().incParseSuccess(5);
        first.stats().incCompute();
        first.stats().incCompute();
        first.stats().incFailure();
        first.stats().incWriteBackSuccess();

        assertEquals(5, first.stats().parseSuccessSegments());
        assertEquals(2, first.stats().computeCount());
        assertEquals(1, first.stats().failureCount());
        assertEquals(1, first.stats().writeBackSuccess());

        // 跨重启可读：新调度器重新加载同一统计文件
        RescanScheduler reopened = new RescanScheduler(null, statsFile);
        assertEquals(5, reopened.stats().parseSuccessSegments());
        assertEquals(2, reopened.stats().computeCount());
        assertEquals(1, reopened.stats().failureCount());
        assertEquals(1, reopened.stats().writeBackSuccess());
    }

    @Test
    void rescanRunsOnBackgroundThread_andStopsGracefully() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> callbackThread = new AtomicReference<>();
        RescanScheduler scheduler = new RescanScheduler((view, payload) -> {
            callbackThread.set(Thread.currentThread().getName());
            latch.countDown();
        }, dir.resolve("stats.json"));

        scheduler.start(20);
        try {
            assertTrue(scheduler.running());
            // 运行态：checkAndRescan 不阻塞调用线程，交给后台线程重算并回调
            assertTrue(scheduler.checkAndRescan("V1", "OLD", "NEW"));
            assertTrue(latch.await(2, TimeUnit.SECONDS), "后台线程应触发回调");
            assertNotEquals(Thread.currentThread().getName(), callbackThread.get(),
                    "重算在后台线程执行，不阻塞界面线程");
        } finally {
            scheduler.stop();
        }
        assertFalse(scheduler.running(), "调度器可优雅停止");
    }
}
