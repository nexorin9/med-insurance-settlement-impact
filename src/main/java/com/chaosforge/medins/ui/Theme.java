package com.chaosforge.medins.ui;

import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.UIManager;
import java.awt.Font;
import java.util.List;

/**
 * 设计 token 单点定义：配色、间距栅格、字体层级、字体族与控件状态。
 *
 * <p>观感对齐企业级数据治理 / BI 桌面端：冷灰底 + 深蓝主色 + 高密度表格。
 * {@link #apply()} 用 FlatLaf 安装现代 Look and Feel 并登记 UIManager 属性；
 * headless 下只登记状态位，不安装 L&amp;F、不触碰显示设备、不创建窗口，绝不抛异常。
 */
public final class Theme {

    // ---- 契约固定 token ----
    public static final String BG = "#F4F6F9";
    public static final String PRIMARY = "#1F4E79";
    public static final String SUCCESS = "#2E7D32";
    public static final String WARNING = "#B26A00";
    public static final String DANGER = "#C62828";
    public static final String NEUTRAL = "#5A6673";
    public static final int GRID = 8;

    // ---- 扩展语义色（集中在本处）----
    public static final String CARD_BG = "#FFFFFF";
    public static final String INFO = "#1565C0";
    public static final String TEXT = "#1F2933";
    public static final String TEXT_MUTED = "#52606D";
    public static final String TEXT_PLACEHOLDER = "#9AA5B1";
    public static final String BORDER = "#E4E7EB";
    /** 严格口径色标。 */
    public static final String STRICT_MARK = "#1565C0";
    /** 保守口径色标。 */
    public static final String CONSERVATIVE_MARK = "#B26A00";

    // ---- 五级字号 ----
    public static final int FONT_TITLE = 18;
    public static final int FONT_SUBTITLE = 14;
    public static final int FONT_BODY = 13;
    public static final int FONT_TABLE = 12;
    public static final int FONT_MONO = 12;

    // ---- 字体族（中文优先，带 fallback；等宽用于字段名与幂等键）----
    public static final String FONT_FAMILY_UI =
            "Microsoft YaHei, PingFang SC, Source Han Sans SC, Noto Sans CJK SC, sans-serif";
    public static final String FONT_FAMILY_MONO =
            "SFMono-Regular, Consolas, Liberation Mono, monospace";

    // ---- 8px 栅格派生 ----
    public static final int SPACE_HALF = GRID / 2;
    public static final int SPACE_1 = GRID;
    public static final int SPACE_2 = GRID * 2;
    public static final int SPACE_3 = GRID * 3;
    public static final int SPACE_4 = GRID * 4;
    public static final int ROW_HEIGHT = 32;
    public static final int COLUMN_PADDING = 12;

    /** 危险操作按钮的文本图标提示（不使用 emoji）。 */
    public static final String DANGER_ICON_MARK = "!";

    // ---- 按钮 / 输入框四态配色 ----
    public static final String BTN_HOVER_BG = "#2E5F8C";
    public static final String BTN_ACTIVE_BG = "#173B5C";
    public static final String BTN_DISABLED_BG = "#E4E7EB";
    public static final String BTN_LOADING_BG = "#8AA6C1";
    // ---- 表格行三态配色 ----
    public static final String ROW_SELECTED_BG = "#E3EEF7";
    public static final String ROW_HOVER_BG = "#F0F4F9";
    public static final String ROW_DISABLED_BG = "#F7F8FA";

    /** 按钮 / 输入框四态（含 loading），可枚举。 */
    public enum ButtonState {
        NORMAL, HOVER, ACTIVE, DISABLED, LOADING
    }

    /** 表格行状态，可枚举。 */
    public enum RowState {
        NORMAL, SELECTED, HOVER, DISABLED
    }

    private static volatile boolean applied;
    private static volatile boolean headlessFallback;
    private static volatile boolean degraded;

    private Theme() {
    }

    /** 安装 Look and Feel 并登记 token；headless 下只登记状态，不创建窗口，不抛异常。 */
    public static void apply() {
        applied = true;
        if (HeadlessMode.isHeadless()) {
            headlessFallback = true;
            return;
        }
        try {
            FlatLightLaf.setup();
            registerProperties();
        } catch (RuntimeException | LinkageError | java.awt.AWTError ex) {
            // 缺少可用的 Look and Feel 或无图形环境时降级为仅登记状态，不抛异常
            degraded = true;
        }
    }

    private static void registerProperties() {
        UIManager.put("medins.bg", BG);
        UIManager.put("medins.primary", PRIMARY);
        UIManager.put("medins.success", SUCCESS);
        UIManager.put("medins.warning", WARNING);
        UIManager.put("medins.danger", DANGER);
        UIManager.put("medins.neutral", NEUTRAL);
        UIManager.put("medins.cardBg", CARD_BG);
        UIManager.put("medins.grid", GRID);
        UIManager.put("medins.font.title", FONT_TITLE);
        UIManager.put("medins.font.subtitle", FONT_SUBTITLE);
        UIManager.put("medins.font.body", FONT_BODY);
        UIManager.put("medins.font.table", FONT_TABLE);
        UIManager.put("medins.font.mono", FONT_MONO);
        UIManager.put("medins.font.family.ui", FONT_FAMILY_UI);
        UIManager.put("medins.font.family.mono", FONT_FAMILY_MONO);
    }

    public static boolean applied() {
        return applied;
    }

    public static boolean headlessFallback() {
        return headlessFallback;
    }

    public static boolean degraded() {
        return degraded;
    }

    public static List<ButtonState> buttonStates() {
        return List.of(ButtonState.values());
    }

    public static List<RowState> rowStates() {
        return List.of(RowState.values());
    }

    /** 按钮四态各自的背景色 token（可枚举渲染，不触碰 AWT 组件）。 */
    public static String colorFor(ButtonState state) {
        return switch (state) {
            case NORMAL -> PRIMARY;
            case HOVER -> BTN_HOVER_BG;
            case ACTIVE -> BTN_ACTIVE_BG;
            case DISABLED -> BTN_DISABLED_BG;
            case LOADING -> BTN_LOADING_BG;
        };
    }

    /** 表格行三态各自的背景色 token。 */
    public static String colorFor(RowState state) {
        return switch (state) {
            case NORMAL -> CARD_BG;
            case SELECTED -> ROW_SELECTED_BG;
            case HOVER -> ROW_HOVER_BG;
            case DISABLED -> ROW_DISABLED_BG;
        };
    }

    /** 字段名与幂等键使用的等宽字体族。 */
    public static String monoFontFamily() {
        return FONT_FAMILY_MONO;
    }

    /** 正文 / 表格字体，字体族按中文环境带 fallback。 */
    public static Font bodyFont() {
        return new Font(FONT_FAMILY_UI, Font.PLAIN, FONT_BODY);
    }

    /** 表格字体（等宽族渲染字段名与幂等键）。 */
    public static Font tableFont() {
        return new Font(FONT_FAMILY_MONO, Font.PLAIN, FONT_TABLE);
    }

    /** 顶栏大数字字体。 */
    public static Font titleFont() {
        return new Font(FONT_FAMILY_UI, Font.BOLD, FONT_TITLE);
    }

    /** 按 8px 栅格换算间距。 */
    public static int spacing(int units) {
        return units * GRID;
    }
}
