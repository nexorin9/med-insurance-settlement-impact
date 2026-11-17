package com.chaosforge.medins.ui;

import java.awt.GraphicsEnvironment;

/** 无显示环境判定：headless 下主程序走 CLI 通道，UI 类不得创建窗口。 */
public final class HeadlessMode {

    private HeadlessMode() {
    }

    public static boolean isHeadless() {
        return GraphicsEnvironment.isHeadless();
    }
}
