package com.chaosforge.medins.ui;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTree;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 三栏工作台主窗口：顶栏（三个大数字 + 写类开关 + 模型可用性） + 左视图树 +
 * 中央影响面表 + 右详情抽屉 + 底部状态条。
 *
 * <p>仅在非 headless 环境构造；所有布局信息同时沉淀在 {@link UiModel} 纯数据里，
 * headless 测试只断言 UiModel，不构造本窗口。顶栏计数经 UiModel 回调刷新，不轮询数据库。
 */
public final class MainFrame extends JFrame {

    private final UiModel model;

    private JLabel parsedLabel;
    private JLabel computedLabel;
    private JLabel failedLabel;
    private JLabel bannerLabel;
    private StatusBar statusBar;
    private JSplitPane outerSplit;
    private JSplitPane rightSplit;

    private final UiModel.Listener listener = new UiModel.Listener() {
        @Override
        public void onCountersChanged(Map<String, Integer> counters) {
            if (parsedLabel != null) {
                parsedLabel.setText("解析 " + counters.getOrDefault("解析", 0));
                computedLabel.setText("计算 " + counters.getOrDefault("计算", 0));
                failedLabel.setText("失败 " + counters.getOrDefault("失败", 0));
            }
        }

        @Override
        public void onModelAvailabilityChanged(boolean available, String banner) {
            if (bannerLabel != null) {
                bannerLabel.setText(banner == null ? "" : banner);
            }
        }
    };

    public MainFrame(UiModel model) {
        super("医保结算清单取数影响分析");
        // headless 下 JFrame 构造本身即抛 HeadlessException；这里再显式拦一道，保证不创建窗口。
        if (HeadlessMode.isHeadless()) {
            throw new IllegalStateException("无显示环境不构造主窗口，请走 headless CLI 通道");
        }
        this.model = Objects.requireNonNull(model);
        setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE); // 关窗只隐藏不退出（托盘常驻）
        model.addListener(listener);
    }

    public void buildComponents() {
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.SPACE_2, Theme.SPACE_1));
        top.setBackground(Color.decode(Theme.CARD_BG));
        JLabel brand = new JLabel("医保结算清单取数影响分析");
        brand.setFont(Theme.titleFont());
        brand.setForeground(Color.decode(Theme.PRIMARY));
        top.add(brand);
        parsedLabel = new JLabel("解析 0");
        computedLabel = new JLabel("计算 0");
        failedLabel = new JLabel("失败 0");
        for (JLabel label : List.of(parsedLabel, computedLabel, failedLabel)) {
            label.setFont(Theme.titleFont());
            label.setForeground(Color.decode(Theme.PRIMARY));
            top.add(label);
        }
        JLabel writeSwitch = new JLabel(model.writeSwitchOn() ? "写类动作：开" : "写类动作：关（默认）");
        writeSwitch.setFont(Theme.bodyFont());
        top.add(writeSwitch);
        JLabel modelState = new JLabel(model.modelAvailable() ? "模型：可用" : "模型：不可达");
        modelState.setFont(Theme.bodyFont());
        modelState.setForeground(Color.decode(Theme.WARNING));
        top.add(modelState);
        bannerLabel = new JLabel(model.banner());
        bannerLabel.setFont(Theme.bodyFont());
        bannerLabel.setForeground(Color.decode(Theme.WARNING));

        // 左视图树（按视图名分组，标注摘要与「已过期」），选中即刷新中央表
        JComponent left = new JScrollPane(new JTree(model.viewTreeNodes().toArray()));
        JComponent center = new ImpactTablePanel(model);
        String detail = model.detailText() == null || model.detailText().isBlank()
                ? String.join("\n", model.detailSections()) : model.detailText();
        JTextArea detailArea = new JTextArea(detail);
        detailArea.setEditable(false);
        detailArea.setLineWrap(true);
        detailArea.setWrapStyleWord(true);
        detailArea.setFont(Theme.bodyFont());
        JComponent right = new JScrollPane(detailArea);

        rightSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, center, right);
        outerSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, rightSplit);
        applyRatios();

        statusBar = new StatusBar(model);

        JPanel north = new JPanel(new BorderLayout());
        north.add(top, BorderLayout.CENTER);
        north.add(bannerLabel, BorderLayout.SOUTH);

        setLayout(new BorderLayout());
        add(north, BorderLayout.NORTH);
        add(outerSplit, BorderLayout.CENTER);
        add(statusBar, BorderLayout.SOUTH);
        refreshFromModel();
    }

    /** 1280 档比例：左 18% / 中 56% / 右 26%。 */
    private void applyRatios() {
        List<Integer> ratios = model.splitRatios();
        int left = ratios.get(0);
        int center = ratios.get(1);
        int right = ratios.get(2);
        int total = left + center + right;
        outerSplit.setResizeWeight((double) left / total);
        rightSplit.setResizeWeight((double) center / (center + right));
    }

    private void refreshFromModel() {
        Map<String, Integer> counters = model.topCounters();
        parsedLabel.setText("解析 " + counters.getOrDefault("解析", 0));
        computedLabel.setText("计算 " + counters.getOrDefault("计算", 0));
        failedLabel.setText("失败 " + counters.getOrDefault("失败", 0));
        bannerLabel.setText(model.banner());
        statusBar.refresh(model);
    }
}
