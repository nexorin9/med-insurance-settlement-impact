package com.chaosforge.medins.ui;

import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 字体与 DPI 兜底。
 *
 * <p>优先使用系统可用中文字体，缺失时按 fallback 链逐级尝试，并给出可读提示；
 * 高 DPI 下按缩放系数调整列宽与行高，避免文字截断。
 * headless 下不访问显示设备，仅凭逻辑字体兜底即可返回确定结果。
 */
public final class FontFallback {

    /** 中文字体 fallback 链，末端是 Java 保证可用的逻辑字体。 */
    public static final String[] FALLBACK_CHAIN = {
            "Microsoft YaHei", "PingFang SC", "Source Han Sans SC",
            "Noto Sans CJK SC", "WenQuanYi Micro Hei", "SimHei", "SansSerif"
    };

    /** 高密度表格基准行高与列宽（px）。 */
    public static final int BASE_ROW_HEIGHT = 32;
    public static final int BASE_COLUMN_WIDTH = 120;

    private static final Set<String> LOGICAL_FONTS = Set.of(
            "SansSerif", "Serif", "Monospaced", "Dialog", "DialogInput");
    private static final double MIN_SCALE = 0.5d;
    private static final double MAX_SCALE = 3.0d;

    private FontFallback() {
    }

    /** 解析出实际可用的字体族名。 */
    public static String resolve(String... preferred) {
        return resolveInternal(preferred).font;
    }

    /** 解析并附带可读提示（首选是否可用、回退到了哪一级）。 */
    public static String resolveWithHint(String... preferred) {
        return resolveInternal(preferred).hint;
    }

    private static Resolution resolveInternal(String... preferred) {
        Set<String> available = availableFamilies();
        List<String> chain = new ArrayList<>();
        if (preferred != null) {
            for (String candidate : preferred) {
                if (candidate != null && !candidate.isBlank() && !chain.contains(candidate.trim())) {
                    chain.add(candidate.trim());
                }
            }
        }
        for (String candidate : FALLBACK_CHAIN) {
            if (!chain.contains(candidate)) {
                chain.add(candidate);
            }
        }

        String first = firstPreferred(preferred);
        for (String candidate : chain) {
            if (isLogical(candidate) || available.contains(candidate)) {
                return new Resolution(candidate, hintFor(candidate, first));
            }
        }
        // 理论上不可达：FALLBACK_CHAIN 末端是逻辑字体
        return new Resolution("SansSerif", "无可用中文字体，已回退到逻辑字体 SansSerif（提示：请安装中文字体）");
    }

    private static String hintFor(String chosen, String first) {
        if (chosen.equals(first)) {
            return "字体可用：" + chosen;
        }
        if (isLogical(chosen)) {
            return "未找到可用中文字体，已按 fallback 链回退到逻辑字体 " + chosen
                    + "（提示：请安装中文字体以获得最佳观感）";
        }
        return "首选字体不可用，已按 fallback 链逐级尝试并回退到：" + chosen
                + "（提示：可安装首选字体以恢复目标观感）";
    }

    private static String firstPreferred(String... preferred) {
        if (preferred != null) {
            for (String candidate : preferred) {
                if (candidate != null && !candidate.isBlank()) {
                    return candidate.trim();
                }
            }
        }
        return "";
    }

    private static boolean isLogical(String family) {
        return LOGICAL_FONTS.contains(family);
    }

    private static Set<String> availableFamilies() {
        if (HeadlessMode.isHeadless()) {
            // 不访问显示设备：仅凭逻辑字体兜底
            return Collections.emptySet();
        }
        try {
            String[] names = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
            return new HashSet<>(Arrays.asList(names));
        } catch (RuntimeException | LinkageError ex) {
            return Collections.emptySet();
        }
    }

    /** 按 DPI 缩放系数调整任意基准像素值。 */
    public static int scaled(int basePx, double dpiScale) {
        return Math.max(1, (int) Math.round(basePx * clamp(dpiScale)));
    }

    public static int scaledRowHeight(double dpiScale) {
        return scaled(BASE_ROW_HEIGHT, dpiScale);
    }

    public static int scaledColumnWidth(double dpiScale) {
        return scaled(BASE_COLUMN_WIDTH, dpiScale);
    }

    /** 从系统属性读取缩放系数，缺省 1.0，并钳制到合理区间。 */
    public static double defaultScale() {
        String raw = System.getProperty("medins.ui.dpi.scale");
        if (raw == null || raw.isBlank()) {
            return 1.0d;
        }
        try {
            return clamp(Double.parseDouble(raw.trim()));
        } catch (NumberFormatException ex) {
            return 1.0d;
        }
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || value < MIN_SCALE) {
            return MIN_SCALE;
        }
        return Math.min(value, MAX_SCALE);
    }

    /** 解析结果（字体族 + 提示）。 */
    private static final class Resolution {
        private final String font;
        private final String hint;

        Resolution(String font, String hint) {
            this.font = font;
            this.hint = hint;
        }
    }
}
