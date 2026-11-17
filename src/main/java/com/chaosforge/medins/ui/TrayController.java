package com.chaosforge.medins.ui;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 托盘常驻、关窗不退出、变更气泡提醒。
 *
 * <p>核心能力是纯状态模型：菜单项、关窗隐藏但进程常驻、气泡文案、点击定位、同视图合并、
 * 托盘不可用降级提示，均可在 headless 下脱离真实 {@link SystemTray} 断言。
 * 真正的托盘图标与菜单只在 {@link #install()}（非 headless 且托盘可用）里创建，
 * 类加载与构造期不触碰任何 AWT 窗口对象。
 */
public final class TrayController {

    public static final String MENU_OPEN = "打开主窗口";
    public static final String MENU_RESCAN = "立即重算";
    public static final String MENU_EXPORT = "导出变更单";
    public static final String MENU_EXIT = "退出";

    /** 同一视图在此时间窗内的多次变化合并为一条气泡。 */
    private static final long MERGE_WINDOW_MILLIS = 2000L;

    private final boolean traySupported;

    private boolean running = true;
    private boolean windowVisible = true;
    private String locatedView = "";
    private String lastBubbleText = "";
    private String degradedHint = "";
    private int bubbleCount;
    private final Map<String, Long> lastEmittedAt = new HashMap<>();

    public TrayController() {
        this(detectTray());
    }

    /** 显式注入托盘可用性，便于 headless 测试断言两种分支。 */
    public TrayController(boolean traySupported) {
        this.traySupported = traySupported;
        if (!traySupported) {
            degradedHint = "系统托盘不可用：常驻能力已降级为「隐藏窗口 + 状态栏提示」，"
                    + "关窗后程序仍在后台运行；请在状态栏查看提示，退出请从菜单明确操作。";
        }
    }

    private static boolean detectTray() {
        if (HeadlessMode.isHeadless()) {
            // headless 下不调用 SystemTray，避免访问显示设备
            return false;
        }
        try {
            return SystemTray.isSupported();
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    /** 托盘菜单四项齐备。 */
    public List<String> menuItems() {
        return List.of(MENU_OPEN, MENU_RESCAN, MENU_EXPORT, MENU_EXIT);
    }

    /** 关闭主窗口只隐藏，不退出进程，托盘仍在。 */
    public void hideOnClose() {
        windowVisible = false;
    }

    /** 仅经菜单明确操作才退出。 */
    public void exit() {
        running = false;
        windowVisible = false;
    }

    public boolean running() {
        return running;
    }

    public boolean windowVisible() {
        return windowVisible;
    }

    public boolean trayActive() {
        return traySupported && running;
    }

    public boolean traySupported() {
        return traySupported;
    }

    public boolean degraded() {
        return !traySupported;
    }

    /** 降级提示须明确告知用户常驻能力不可用，不静默失效。 */
    public String degradedHint() {
        return degradedHint;
    }

    /**
     * 收到重算回调（含视图名与变化前后摘要）时生成气泡；
     * 返回是否真正弹出（同一视图短时间内多次变化合并为一条，返回 false）。
     */
    public boolean onViewChanged(String viewName, String beforeSummary, String afterSummary) {
        long now = System.currentTimeMillis();
        Long last = lastEmittedAt.get(viewName);
        boolean merged = last != null && (now - last) < MERGE_WINDOW_MILLIS;
        lastBubbleText = bubbleText(viewName, beforeSummary, afterSummary);
        if (merged) {
            return false;
        }
        lastEmittedAt.put(viewName, now);
        bubbleCount++;
        return true;
    }

    /** 气泡文案只含视图名与变化前后摘要，不含患者明细与凭据。 */
    public static String bubbleText(String viewName, String beforeSummary, String afterSummary) {
        return viewName + " 已变更（摘要 " + shortDigest(beforeSummary) + " → "
                + shortDigest(afterSummary) + "），影响面已刷新；点击打开主窗口并定位到该视图。";
    }

    public String lastBubbleText() {
        return lastBubbleText;
    }

    public int bubbleCount() {
        return bubbleCount;
    }

    /** 点击气泡：打开主窗口并定位到该视图。 */
    public void openAndLocate(String viewName) {
        windowVisible = true;
        locatedView = viewName == null ? "" : viewName;
    }

    public String locatedView() {
        return locatedView;
    }

    /** 创建真实 SystemTray 图标与菜单；仅非 headless 且托盘可用时生效。 */
    public void install() {
        if (HeadlessMode.isHeadless() || !traySupported || !SystemTray.isSupported()) {
            return;
        }
        try {
            SystemTray tray = SystemTray.getSystemTray();
            PopupMenu menu = new PopupMenu();
            for (String item : menuItems()) {
                menu.add(new MenuItem(item));
            }
            TrayIcon icon = new TrayIcon(renderIcon(), "医保结算清单取数影响分析", menu);
            icon.setImageAutoSize(true);
            tray.add(icon);
        } catch (AWTException | RuntimeException ex) {
            // 安装失败不静默失效：构造/降级路径已给出明确提示与状态位
        }
    }

    private static BufferedImage renderIcon() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(0x1F, 0x4E, 0x79));
        graphics.fillRect(0, 0, 16, 16);
        graphics.setColor(Color.WHITE);
        graphics.drawRect(2, 2, 11, 11);
        graphics.dispose();
        return image;
    }

    private static String shortDigest(String raw) {
        if (raw == null || raw.isBlank()) {
            return "（无）";
        }
        String trimmed = raw.trim();
        return trimmed.length() <= 8 ? trimmed : trimmed.substring(0, 8) + "…";
    }
}
