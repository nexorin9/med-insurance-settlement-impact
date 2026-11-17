package com.chaosforge.medins.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 三栏工作台骨架测试：无显示环境下只断言 UiModel 纯数据与关键文案，绝不 new JFrame。 */
class MainFrameLayoutTest {

    @Test
    @DisplayName("三栏 + 顶栏 + 底部状态条齐全")
    void panelsPresent() {
        UiModel model = UiModel.builder().build();
        List<String> panels = model.panels();
        assertTrue(panels.contains("view-tree"), "左视图树");
        assertTrue(panels.contains("impact-table"), "中央影响面表");
        assertTrue(panels.contains("detail-drawer"), "右详情抽屉");
        assertTrue(panels.contains("top-bar"), "顶栏");
        assertTrue(panels.contains("status-bar"), "底部状态条");
        assertEquals(5, panels.size());
    }

    @Test
    @DisplayName("顶栏三个大数字与写类开关、模型可用性指示")
    void topBarCounters() {
        UiModel model = UiModel.builder().build();
        Map<String, Integer> counters = model.topCounters();
        assertTrue(counters.containsKey("解析"));
        assertTrue(counters.containsKey("计算"));
        assertTrue(counters.containsKey("失败"));
        assertEquals(3, counters.size());
        assertFalse(model.writeSwitchOn(), "写类开关默认关");
        assertTrue(model.modelAvailable());
        assertTrue(model.banner().isEmpty(), "模型可用时无横幅");
    }

    @Test
    @DisplayName("状态条含阶段/耗时/降级提示，模型不可达显示「归因未生成」横幅")
    void statusBarAndBanner() {
        UiModel model = UiModel.builder().build();
        String status = StatusBar.render(model);
        assertTrue(status.contains("阶段"));
        assertTrue(status.contains("耗时"));
        assertTrue(status.contains("降级"));

        model.setModelAvailable(false);
        assertFalse(model.modelAvailable());
        assertEquals("归因未生成", model.banner());
        assertTrue(StatusBar.render(model).contains("归因未生成"));
    }

    @Test
    @DisplayName("顶栏计数经 UiModel 回调刷新，不轮询数据库")
    void countersRefreshViaListener() {
        UiModel model = UiModel.builder().build();
        AtomicReference<Map<String, Integer>> seen = new AtomicReference<>();
        AtomicReference<String> bannerSeen = new AtomicReference<>();
        model.addListener(new UiModel.Listener() {
            @Override
            public void onCountersChanged(Map<String, Integer> counters) {
                seen.set(counters);
            }

            @Override
            public void onModelAvailabilityChanged(boolean available, String banner) {
                bannerSeen.set(banner);
            }
        });

        model.updateCounters(7, 5, 1);
        assertEquals(7, model.topCounters().get("解析"));
        assertEquals(5, model.topCounters().get("计算"));
        assertEquals(1, model.topCounters().get("失败"));
        assertNotNull(seen.get(), "回调应被触发");
        assertEquals(7, seen.get().get("解析"), "回调携带最新计数");

        model.setModelAvailable(false);
        assertEquals("归因未生成", bannerSeen.get(), "模型可用性变化也走回调");
    }

    @Test
    @DisplayName("中央表列、必填排前、JSplitPane 比例与左树「已过期」标记")
    void tableColumnsRatiosAndTree() {
        UiModel model = UiModel.builder().build();
        assertEquals(List.of("分区", "字段", "来源列", "判定", "置信度", "口径", "跳数", "批次"),
                model.tableColumns());
        assertEquals(List.of(18, 56, 26), model.splitRatios(), "1280 档 18%/56%/26%");
        assertEquals(100, model.splitRatios().stream().mapToInt(Integer::intValue).sum());
        assertTrue(model.viewTreeNodes().stream().anyMatch(node -> node.contains("已过期")),
                "左树按视图分组标注已过期");
        assertTrue(model.viewTreeNodes().stream().anyMatch(node -> node.contains("摘要")),
                "左树标注最近一次摘要");
        assertTrue(model.detailSections().contains("待人工核对条目"));
    }

    @Test
    @DisplayName("两档响应式：1280 三栏全展开、768 紧凑，均无横向滚动条")
    void responsiveLayout() {
        UiModel model = UiModel.builder().build();
        assertEquals("three-column", model.layoutMode(1280));
        assertEquals("compact", model.layoutMode(768));
        assertTrue(model.leftTreeCollapsed(768), "768 档左树折叠");
        assertTrue(model.detailAsOverlay(768), "768 档详情改覆盖层");
        assertFalse(model.leftTreeCollapsed(1280));
        assertFalse(model.horizontalScrollBar(1280));
        assertFalse(model.horizontalScrollBar(768));
    }
}
