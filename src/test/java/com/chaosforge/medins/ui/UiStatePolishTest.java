package com.chaosforge.medins.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** UI 状态打磨测试：三态、toast、二次确认、两档响应式（headless 安全）。 */
class UiStatePolishTest {

    @Test
    @DisplayName("加载/空/错误三态各有渲染分支且文案各不相同")
    void threeStatesDistinct() {
        assertEquals(3, UiStatePolish.states().size());
        String loading = UiStatePolish.textFor(UiStatePolish.STATE_LOADING);
        String empty = UiStatePolish.textFor(UiStatePolish.STATE_EMPTY);
        String error = UiStatePolish.textFor(UiStatePolish.STATE_ERROR);
        assertNotEquals(loading, empty);
        assertNotEquals(empty, error);
        assertNotEquals(loading, error);

        assertTrue(loading.contains("骨架行"));
        assertTrue(UiStatePolish.hasSkeletonRows(UiStatePolish.STATE_LOADING));
        assertTrue(empty.contains("选择视图"));
        assertTrue(UiStatePolish.hasNextStep(UiStatePolish.STATE_EMPTY));
        assertTrue(error.contains("重试"));
        assertTrue(error.contains("建议"));
        assertTrue(UiStatePolish.hasRetryEntry(UiStatePolish.STATE_ERROR));
        assertThrows(IllegalArgumentException.class, () -> UiStatePolish.textFor("unknown"));
    }

    @Test
    @DisplayName("toast 成功/警告/失败三档可枚举")
    void toastKinds() {
        assertEquals(List.of("成功", "警告", "失败"), UiStatePolish.toastKinds());
        for (String kind : UiStatePolish.toastKinds()) {
            assertTrue(UiStatePolish.toast(kind, "示例").contains(kind));
            assertTrue(Toast.isKnown(kind));
        }
        assertEquals(Theme.SUCCESS, Toast.colorFor("成功"));
        assertEquals(Theme.WARNING, Toast.colorFor("警告"));
        assertEquals(Theme.DANGER, Toast.colorFor("失败"));
    }

    @Test
    @DisplayName("破坏性操作未确认不执行，确认框写明后果与影响条数")
    void destructiveActionsNeedConfirmation() {
        List<String> actions = UiStatePolish.confirmableActions();
        assertEquals(3, actions.size());
        assertTrue(actions.contains("撤回人工确认"));
        assertTrue(actions.contains("开启写类开关"));
        assertTrue(actions.contains("批量标记为已确认"));

        String message = UiStatePolish.confirmationMessage("撤回人工确认", 3);
        assertTrue(message.contains("影响 3 条记录"), "写明影响条数");
        assertTrue(message.contains("留痕"), "写明后果");
        assertTrue(message.contains("未确认不执行"));

        AtomicBoolean executed = new AtomicBoolean(false);
        boolean notConfirmed = UiStatePolish.executeIfConfirmed(
                "撤回人工确认", 3, false, () -> executed.set(true));
        assertFalse(notConfirmed);
        assertFalse(executed.get(), "未确认不得执行任务");

        boolean confirmed = UiStatePolish.executeIfConfirmed(
                "撤回人工确认", 3, true, () -> executed.set(true));
        assertTrue(confirmed);
        assertTrue(executed.get(), "确认后执行任务");
        assertFalse(UiStatePolish.executeIfConfirmed("未知动作", 1, true, () -> {
        }), "清单外动作不执行");
    }

    @Test
    @DisplayName("两档宽度下组件布局可断言，均无横向滚动条")
    void responsiveLayouts() {
        assertEquals("three-column", UiStatePolish.layout(1280));
        assertEquals("compact", UiStatePolish.layout(768));
        assertFalse(UiStatePolish.leftTreeCollapsed(1280));
        assertTrue(UiStatePolish.leftTreeCollapsed(768), "768 档左树折叠");
        assertTrue(UiStatePolish.detailAsOverlay(768), "768 档详情改覆盖层");
        assertFalse(UiStatePolish.detailAsOverlay(1280));
        assertFalse(UiStatePolish.horizontalScrollBar(1280));
        assertFalse(UiStatePolish.horizontalScrollBar(768));
        assertEquals(List.of(18, 56, 26), UiStatePolish.splitRatios(1280));
    }
}
