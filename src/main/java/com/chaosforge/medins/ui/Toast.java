package com.chaosforge.medins.ui;

import java.util.List;

/**
 * 轻量的 toast 文案模型（纯数据，不弹真实对话框、不创建窗口）。
 *
 * <p>成功 / 警告 / 失败三档；短暂提示不打断操作，重要信息同步进状态条留痕。
 */
public final class Toast {

    private static final List<String> KINDS = List.of("成功", "警告", "失败");

    private Toast() {
    }

    public static List<String> kinds() {
        return KINDS;
    }

    /** 生成一档 toast 文案。 */
    public static String format(String kind, String text) {
        String body = text == null ? "" : text;
        return "[" + kind + "] " + body + " ｜ 短暂提示不打断操作，重要信息同步进状态条留痕";
    }

    /** 一档 toast 对应的语义色 token。 */
    public static String colorFor(String kind) {
        return switch (kind) {
            case "成功" -> Theme.SUCCESS;
            case "警告" -> Theme.WARNING;
            case "失败" -> Theme.DANGER;
            default -> Theme.NEUTRAL;
        };
    }

    public static boolean isKnown(String kind) {
        return KINDS.contains(kind);
    }
}
