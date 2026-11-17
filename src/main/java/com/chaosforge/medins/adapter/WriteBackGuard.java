package com.chaosforge.medins.adapter;

import com.chaosforge.medins.model.ImpactRecord;

import java.util.List;

/**
 * 回写前置守卫 —— 两道闸门缺一不可：
 * <ol>
 *   <li>操作人须具备「接口变更登记」权限，无权限零写入（只出草稿并导出变更单）；</li>
 *   <li>写类开关默认关闭，须显式开启才允许真实写入；</li>
 *   <li>缺 {@code confirmed_by / confirmed_at} 或存在未确认行时，禁止构造回写请求
 *       （服务层拦截，不只靠界面置灰）。</li>
 * </ol>
 */
public final class WriteBackGuard {

    private WriteBackGuard() { }

    public static GuardResult check(boolean permission, boolean writeSwitchOn,
                                    List<ImpactRecord> records) {
        List<ImpactRecord> safe = records == null ? List.of() : records;
        int eligibleCount = 0;
        for (ImpactRecord r : safe) {
            if (r != null && r.isWriteBackEligible()) {
                eligibleCount++;
            }
        }
        if (!permission) {
            return new GuardResult(false, "无「接口变更登记」权限，禁止回写（零写入）", eligibleCount);
        }
        if (!writeSwitchOn) {
            return new GuardResult(false, "写类开关默认关闭，须显式开启后才允许真实写入", eligibleCount);
        }
        if (safe.isEmpty()) {
            return new GuardResult(false, "无可回写的影响面记录，禁止构造空回写请求", eligibleCount);
        }
        if (eligibleCount != safe.size()) {
            return new GuardResult(false,
                    "存在未确认行或缺 confirmed_by / confirmed_at，禁止构造回写请求", eligibleCount);
        }
        return new GuardResult(true, "允许回写", eligibleCount);
    }

    /**
     * 带执行动作的守卫：只有三道闸门全部通过才运行 {@code writeAction}（真实 POST / 计数桩）。
     * 供服务层与单测共用，保证「零写入」「开启开关后请求才发出」可被真实断言。
     */
    public static GuardResult checkAndRun(boolean permission, boolean writeSwitchOn,
                                          List<ImpactRecord> records, Runnable writeAction) {
        GuardResult result = check(permission, writeSwitchOn, records);
        if (result.allowed() && writeAction != null) {
            writeAction.run();
        }
        return result;
    }
}
