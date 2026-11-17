package com.chaosforge.medins.ui;

import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;

/**
 * 底部状态条：显示当前阶段、耗时与降级提示；模型不可达时追加「归因未生成」横幅。
 *
 * <p>组件在实例构造时才创建 JLabel，类加载期不触碰任何 AWT/Swing 对象；
 * 纯数据渲染方法 {@link #render(UiModel)} 可在 headless 下直接断言。
 */
public final class StatusBar extends JPanel {

    private JLabel statusLabel;
    private JLabel bannerLabel;

    public StatusBar(UiModel model) {
        statusLabel = new JLabel();
        bannerLabel = new JLabel();
        setLayout(new BorderLayout());
        add(statusLabel, BorderLayout.WEST);
        add(bannerLabel, BorderLayout.EAST);
        refresh(model);
    }

    public void refresh(UiModel model) {
        statusLabel.setText(model.statusText());
        String banner = model.banner();
        bannerLabel.setText(banner == null || banner.isBlank() ? "" : " ｜ " + banner);
    }

    /** 纯数据渲染：headless 下可断言状态条文案。 */
    public static String render(UiModel model) {
        String banner = model.banner();
        return banner == null || banner.isBlank()
                ? model.statusText()
                : model.statusText() + " ｜ " + banner;
    }
}
