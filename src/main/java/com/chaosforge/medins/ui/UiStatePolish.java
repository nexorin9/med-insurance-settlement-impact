package com.chaosforge.medins.ui;

import java.util.List;

/**
 * UI 状态与打磨：加载 / 空 / 错误三态、toast 三档、破坏性操作二次确认、两档响应式。
 *
 * <p>全部为纯静态方法，不弹真实对话框、不创建窗口；二次确认只生成文案并依据 confirmed 位
 * 决定是否执行任务，真实对话框由调用方在非 headless 环境弹出。headless 下均可断言。
 */
public final class UiStatePolish {

    public static final String STATE_LOADING = "loading";
    public static final String STATE_EMPTY = "empty";
    public static final String STATE_ERROR = "error";

    private static final String LOADING_TEXT =
            "加载态：骨架行占位 + 进度提示，正在计算影响面…";
    private static final String EMPTY_TEXT =
            "空态：尚未选择视图，请从左侧选择取数视图；下一步可点击「载入样例」导入样例视图。";
    private static final String ERROR_TEXT =
            "错误态：加载失败（只读库或模型端点不可达）。建议：检查配置后点击「重试」，或查看状态条留痕。";

    private UiStatePolish() {
    }

    public static List<String> states() {
        return List.of(STATE_LOADING, STATE_EMPTY, STATE_ERROR);
    }

    /** 三态各有不同渲染分支文案。 */
    public static String textFor(String state) {
        return switch (state) {
            case STATE_LOADING -> LOADING_TEXT;
            case STATE_EMPTY -> EMPTY_TEXT;
            case STATE_ERROR -> ERROR_TEXT;
            default -> throw new IllegalArgumentException("未知状态：" + state);
        };
    }

    public static boolean hasSkeletonRows(String state) {
        return STATE_LOADING.equals(state);
    }

    public static boolean hasNextStep(String state) {
        return STATE_EMPTY.equals(state);
    }

    public static boolean hasRetryEntry(String state) {
        return STATE_ERROR.equals(state);
    }

    /** toast 成功 / 警告 / 失败三档可枚举。 */
    public static List<String> toastKinds() {
        return Toast.kinds();
    }

    public static String toast(String kind, String text) {
        return Toast.format(kind, text);
    }

    /** 三类破坏性操作均需二次确认。 */
    public static List<String> confirmableActions() {
        return List.of("撤回人工确认", "开启写类开关", "批量标记为已确认");
    }

    /** 确认框文案写明后果与影响条数。 */
    public static String confirmationMessage(String action, int affectedCount) {
        return "二次确认 · " + action + "：将影响 " + affectedCount + " 条记录；"
                + consequence(action) + " 该动作会留痕且不可抹除。未确认不执行，确认后继续？";
    }

    private static String consequence(String action) {
        return switch (action) {
            case "撤回人工确认" -> "将撤销这些记录的人工确认状态（撤回是追加留痕，不是抹掉原记录）；";
            case "开启写类开关" -> "将允许构造台账回写请求（默认关闭，开启后仍需逐行人工确认）；";
            case "批量标记为已确认" -> "将把这些记录批量标记为已人工确认（仅人工动作可推进 CONFIRMED）；";
            default -> "将改变这些记录的业务状态；";
        };
    }

    /** 未确认不执行；已确认且动作在清单内才执行任务。 */
    public static boolean executeIfConfirmed(String action, int affectedCount,
                                             boolean confirmed, Runnable task) {
        if (!confirmed || !confirmableActions().contains(action)) {
            return false;
        }
        if (task != null) {
            task.run();
        }
        return true;
    }

    /** 1280 档三栏全展开，768 档左树折叠 + 详情抽屉改覆盖层。 */
    public static String layout(int width) {
        return width >= 1024 ? "three-column" : "compact";
    }

    public static boolean leftTreeCollapsed(int width) {
        return "compact".equals(layout(width));
    }

    public static boolean detailAsOverlay(int width) {
        return "compact".equals(layout(width));
    }

    /** 两档宽度下均不出现横向滚动条。 */
    public static boolean horizontalScrollBar(int width) {
        return false;
    }

    /** 两档的栏比例，供 MainFrame 设置 JSplitPane。 */
    public static List<Integer> splitRatios(int width) {
        return "three-column".equals(layout(width))
                ? List.of(18, 56, 26)
                : List.of(0, 72, 28);
    }
}
