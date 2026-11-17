package com.chaosforge.medins.service;

import com.chaosforge.medins.store.RunStats;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * 定时重算调度器：按配置周期比对各取数视图的 {@code sql_digest}，发现变化即触发重算并回调通知。
 *
 * <p>规则：
 * <ul>
 *   <li>摘要变化触发重算并记录变化前后摘要；摘要未变化不触发；</li>
 *   <li>通过 {@code BiConsumer} 回调通知（服务层不直接依赖 Swing 类，气泡由 UI 层订阅回调实现）；</li>
 *   <li>重算在后台线程执行，不阻塞界面；{@link #start(long)} / {@link #stop()} 优雅停止；</li>
 *   <li>运行统计计数由 {@link RunStats} 维护，可持久化、跨重启可读。</li>
 * </ul>
 */
public final class RescanScheduler {

    private final BiConsumer<String, String[]> onChange;
    private final RunStats stats;
    private final List<String[]> changeLog = new ArrayList<>();
    private final ConcurrentLinkedQueue<String[]> pending = new ConcurrentLinkedQueue<>();

    private volatile boolean running = false;
    private ScheduledExecutorService executor;

    public RescanScheduler(BiConsumer<String, String[]> onChange, Path statsFile) {
        this.onChange = onChange == null ? (view, payload) -> { } : onChange;
        this.stats = new RunStats(statsFile);
    }

    /**
     * 比对前后摘要：变化则记录（视图、旧摘要、新摘要）、累加统计、触发回调，返回 true；
     * 未变化返回 false，且不触发任何回调。
     */
    public boolean checkAndRescan(String viewName, String oldDigest, String newDigest) {
        String old = oldDigest == null ? "" : oldDigest;
        String nw = newDigest == null ? "" : newDigest;
        if (old.equals(nw)) {
            return false;
        }
        String view = viewName == null ? "" : viewName;
        synchronized (changeLog) {
            changeLog.add(new String[]{view, old, nw});
        }
        stats.incCompute();
        stats.incParseSuccess(1);
        if (running) {
            // 运行态：交给后台线程处理，不阻塞调用方（界面）
            pending.offer(new String[]{view, old, nw});
        } else {
            // 未启动：同步触发，便于单测直接观察回调
            notify(view, old, nw);
        }
        return true;
    }

    public void start(long intervalMillis) {
        if (running) {
            return;
        }
        running = true;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "medins-rescan");
            t.setDaemon(true);
            return t;
        });
        long period = Math.max(1L, intervalMillis);
        executor.scheduleAtFixedRate(this::drain, 0, period, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        running = false;
        ScheduledExecutorService e = executor;
        executor = null;
        if (e != null) {
            e.shutdownNow();
        }
    }

    public boolean running() {
        return running;
    }

    public RunStats stats() {
        return stats;
    }

    /** 变化前后摘要记录：[视图, 旧摘要, 新摘要]。 */
    public List<String[]> changeLog() {
        synchronized (changeLog) {
            List<String[]> copy = new ArrayList<>();
            for (String[] e : changeLog) {
                copy.add(e.clone());
            }
            return List.copyOf(copy);
        }
    }

    private void drain() {
        String[] item;
        while ((item = pending.poll()) != null) {
            try {
                notify(item[0], item[1], item[2]);
            } catch (RuntimeException e) {
                stats.incFailure();
            }
        }
    }

    private void notify(String view, String oldDigest, String newDigest) {
        try {
            onChange.accept(view, new String[]{"digest_before=" + oldDigest,
                    "digest_after=" + newDigest, "reason=摘要变化触发重算"});
        } catch (RuntimeException e) {
            stats.incFailure();
        }
    }
}
