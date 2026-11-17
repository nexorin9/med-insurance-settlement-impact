package com.chaosforge.medins.adapter;

import com.chaosforge.medins.model.ChangeKind;
import com.chaosforge.medins.model.ImpactRecord;
import com.chaosforge.medins.model.ImpactScope;
import com.chaosforge.medins.model.ImpactStatus;
import com.chaosforge.medins.model.ImpactType;
import com.chaosforge.medins.model.Verdict;
import com.chaosforge.medins.service.AnalysisInput;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 41：回写权限闸门与写类开关默认关闭、确认前置校验。
 * 用计数桩与注入的 HttpTransport 替身，断言「零写入 / 开启开关后请求才发出」，不访问真实网络。
 */
class IfaceRegistryGuardTest {

    private static final String PATH = "/api/v1/interface-entries/E1/field-change-impacts";

    private static ImpactRecord eligible(String key) {
        return base(key).withHumanConfirmation("专员A", "2026-11-09T08:50:00Z");
    }

    /** 状态为 CONFIRMED 但缺 confirmed_by / confirmed_at：不得构造回写请求。 */
    private static ImpactRecord unconfirmed(String key) {
        return base(key).toBuilder().status(ImpactStatus.CONFIRMED).build();
    }

    private static ImpactRecord base(String key) {
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
                .hops(1)
                .batch("<EXAMPLE_BATCH>")
                .verdict(Verdict.MATERIALLY_AFFECTED)
                .status(ImpactStatus.PENDING_CONFIRM)
                .build();
    }

    private static List<ImpactRecord> twoEligible() {
        return List.of(eligible("K-1"), eligible("K-2"));
    }

    @Test
    void noPermission_zeroWrites() {
        AtomicInteger writes = new AtomicInteger();
        GuardResult g = WriteBackGuard.checkAndRun(false, true, twoEligible(), writes::incrementAndGet);
        assertFalse(g.allowed());
        assertEquals(0, writes.get(), "无「接口变更登记」权限时零写入");
        assertTrue(g.reason().contains("权限"));
        assertFalse(WriteBackGuard.check(false, true, twoEligible()).allowed());
    }

    @Test
    void writeSwitchIsOffByDefault() {
        // 输入层：写类开关默认关
        AnalysisInput input = AnalysisInput.builder().viewName("V").changedField("C").build();
        assertFalse(input.writeSwitchOn(), "写类开关默认关闭，须显式开启");

        AtomicInteger writes = new AtomicInteger();
        GuardResult g = WriteBackGuard.checkAndRun(true, false, twoEligible(), writes::incrementAndGet);
        assertFalse(g.allowed());
        assertTrue(g.reason().contains("默认关闭") || g.reason().contains("开关"));
        assertEquals(0, writes.get(), "开关未开时零写入");
    }

    @Test
    void missingConfirmation_noRequest() {
        AtomicInteger writes = new AtomicInteger();
        List<ImpactRecord> records = List.of(unconfirmed("K-1"), unconfirmed("K-2"));
        GuardResult g = WriteBackGuard.checkAndRun(true, true, records, writes::incrementAndGet);
        assertFalse(g.allowed());
        assertEquals(0, g.eligibleCount(), "缺 confirmed_by / confirmed_at 时无记录具备回写资格");
        assertEquals(0, writes.get(), "缺确认字段时不发请求");
        assertTrue(g.reason().contains("确认"));

        // 记录本身 isWriteBackEligible() 为假（服务层拦截依据）
        assertFalse(unconfirmed("K-1").isWriteBackEligible());
        assertTrue(eligible("K-1").isWriteBackEligible());
    }

    @Test
    void switchOnAndAllConfirmed_requestIsSent() {
        CountingTransport fake = new CountingTransport();
        IfaceRegistryClient client = new IfaceRegistryClient("http://registry.local", "TOKEN", fake);
        List<ImpactRecord> records = twoEligible();

        GuardResult g = WriteBackGuard.checkAndRun(true, true, records,
                () -> client.post(PATH, "{}", "K-1"));

        assertTrue(g.allowed(), "权限 + 开关 + 全部确认后放行");
        assertEquals(records.size(), g.eligibleCount());
        assertEquals(1, fake.calls.size(), "开启开关后请求才发出");
        assertEquals(1, client.submittedKeyCount());
    }

    /** 仅记录调用次数的协议替身。 */
    private static final class CountingTransport implements HttpTransport {
        final List<String> calls = new ArrayList<>();

        @Override
        public RawResponse execute(String method, String url, Map<String, String> headers,
                                   String body, int timeoutSeconds) throws IOException {
            calls.add(method + " " + url);
            return new RawResponse(200, "{\"ok\":true}");
        }
    }
}
