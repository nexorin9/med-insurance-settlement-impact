package com.chaosforge.medins.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 托盘常驻测试：全部断言纯状态位，不调用 install()、不创建真实 SystemTray 对象。 */
class TrayControllerTest {

    @Test
    @DisplayName("托盘菜单四项齐备")
    void menuItemsComplete() {
        TrayController tray = new TrayController(true);
        assertEquals(List.of("打开主窗口", "立即重算", "导出变更单", "退出"), tray.menuItems());
    }

    @Test
    @DisplayName("关窗只隐藏不退出，进程与托盘仍在；退出须经菜单明确操作")
    void closeHidesButDoesNotExit() {
        TrayController tray = new TrayController(true);
        assertTrue(tray.running());
        assertTrue(tray.windowVisible());

        tray.hideOnClose();
        assertFalse(tray.windowVisible(), "窗口隐藏");
        assertTrue(tray.running(), "进程仍在");
        assertTrue(tray.trayActive(), "托盘仍在");

        tray.openAndLocate("V_BILL_FEE");
        assertTrue(tray.windowVisible(), "点击气泡打开主窗口");
        assertEquals("V_BILL_FEE", tray.locatedView(), "并定位到该视图");

        tray.exit();
        assertFalse(tray.running(), "仅菜单明确操作才退出");
    }

    @Test
    @DisplayName("托盘不可用时降级为隐藏窗口 + 状态栏提示，并明确告知用户")
    void degradedWhenTrayUnavailable() {
        TrayController tray = new TrayController(false);
        assertTrue(tray.degraded());
        assertFalse(tray.traySupported());
        assertFalse(tray.trayActive());
        String hint = tray.degradedHint();
        assertTrue(hint.contains("托盘"), "明确说明托盘不可用");
        assertTrue(hint.contains("降级"), "明确说明已降级");
        assertTrue(hint.contains("隐藏窗口"));
        assertTrue(hint.contains("状态栏"));
        assertTrue(hint.contains("仍在后台运行"), "告知常驻能力仍在，不静默失效");
    }

    @Test
    @DisplayName("气泡文案含视图名与变化前后摘要，不含患者明细与凭据")
    void bubbleTextNoSensitiveContent() {
        String text = TrayController.bubbleText("V_BILL_FEE", "a1b2c3d4旧摘要", "e5f6a7b8新摘要");
        assertTrue(text.contains("V_BILL_FEE"), "含视图名");
        assertTrue(text.contains("a1b2c3d4"), "含变化前摘要");
        assertTrue(text.contains("e5f6a7b8"), "含变化后摘要");
        assertTrue(text.contains("→"));
        assertTrue(text.contains("定位"), "点击打开主窗口并定位视图");

        String lower = text.toLowerCase();
        for (String secret : List.of("患者", "身份证", "口令", "密码", "bearer", "token", "api_key", "凭据")) {
            assertFalse(lower.contains(secret), "气泡不得含敏感信息：" + secret);
        }
    }

    @Test
    @DisplayName("同视图短时间多次变化合并为一条，不同视图各自提示")
    void mergesRepeatedChanges() {
        TrayController tray = new TrayController(true);
        assertTrue(tray.onViewChanged("V_BILL_FEE", "aaaa1111", "bbbb2222"), "首次变化弹出");
        assertFalse(tray.onViewChanged("V_BILL_FEE", "bbbb2222", "cccc3333"), "同视图短时间再次变化应合并");
        assertEquals(1, tray.bubbleCount(), "合并为一条");
        assertTrue(tray.lastBubbleText().contains("cccc3333"), "合并后用最新变化摘要");

        assertTrue(tray.onViewChanged("V_PATIENT_BASIC", "dddd4444", "eeee5555"), "不同视图另发一条");
        assertEquals(2, tray.bubbleCount());
    }
}
