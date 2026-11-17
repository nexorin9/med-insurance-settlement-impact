package com.chaosforge.medins.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 设计 token 与基础组件四态测试（headless 安全）。 */
class ThemeTokenTest {

    @Test
    @DisplayName("色值、间距栅格、五级字号与字体族齐备")
    void tokensComplete() {
        assertEquals("#F4F6F9", Theme.BG);
        assertEquals("#1F4E79", Theme.PRIMARY);
        assertEquals("#2E7D32", Theme.SUCCESS);
        assertEquals("#B26A00", Theme.WARNING);
        assertEquals("#C62828", Theme.DANGER);
        assertEquals("#5A6673", Theme.NEUTRAL);
        assertEquals(8, Theme.GRID);
        assertEquals(16, Theme.spacing(2));
        assertEquals(4, Theme.SPACE_HALF);
        assertEquals(32, Theme.ROW_HEIGHT);

        assertTrue(Theme.FONT_TITLE > Theme.FONT_SUBTITLE, "标题字号应大于小标题");
        assertTrue(Theme.FONT_SUBTITLE > Theme.FONT_BODY, "小标题字号应大于正文");
        assertTrue(Theme.FONT_BODY >= Theme.FONT_TABLE);
        assertTrue(Theme.FONT_TABLE >= Theme.FONT_MONO);
        assertTrue(Theme.FONT_FAMILY_UI.contains("Microsoft YaHei"));
        assertTrue(Theme.FONT_FAMILY_UI.contains("PingFang SC"));
        assertTrue(Theme.FONT_FAMILY_MONO.contains("monospace"));
        assertTrue(Theme.monoFontFamily().contains("Consolas"), "等宽字体用于字段名与幂等键");
        assertFalse(Theme.DANGER_ICON_MARK.isEmpty(), "危险操作按钮带图标提示");
        assertEquals(Theme.PRIMARY, Theme.colorFor(Theme.ButtonState.NORMAL));
    }

    @Test
    @DisplayName("按钮四态与表格行三态可枚举，渲染取值不抛异常")
    void statesEnumerableAndRenderable() {
        List<Theme.ButtonState> buttons = Theme.buttonStates();
        assertTrue(buttons.contains(Theme.ButtonState.HOVER));
        assertTrue(buttons.contains(Theme.ButtonState.ACTIVE));
        assertTrue(buttons.contains(Theme.ButtonState.DISABLED));
        assertTrue(buttons.contains(Theme.ButtonState.LOADING));
        assertEquals(5, buttons.size());

        List<Theme.RowState> rows = Theme.rowStates();
        assertTrue(rows.contains(Theme.RowState.SELECTED));
        assertTrue(rows.contains(Theme.RowState.HOVER));
        assertTrue(rows.contains(Theme.RowState.DISABLED));

        for (Theme.ButtonState state : buttons) {
            assertDoesNotThrow(() -> Theme.colorFor(state));
            assertTrue(Theme.colorFor(state).startsWith("#"), "按钮态须有可枚举色值");
        }
        for (Theme.RowState state : rows) {
            assertDoesNotThrow(() -> Theme.colorFor(state));
            assertTrue(Theme.colorFor(state).startsWith("#"), "表格行态须有可枚举色值");
        }
        assertEquals(Theme.ROW_SELECTED_BG, Theme.colorFor(Theme.RowState.SELECTED));
        assertEquals(Theme.ROW_HOVER_BG, Theme.colorFor(Theme.RowState.HOVER));
        assertEquals(Theme.ROW_DISABLED_BG, Theme.colorFor(Theme.RowState.DISABLED));
    }

    @Test
    @DisplayName("Theme.apply 在 headless 下也不抛异常，只登记状态")
    void applyHeadlessSafe() {
        assertDoesNotThrow(Theme::apply);
        assertTrue(Theme.applied());
        if (HeadlessMode.isHeadless()) {
            assertTrue(Theme.headlessFallback(), "无显示环境应登记 headless 降级状态");
        }
    }
}
