package com.chaosforge.medins.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 工作台布局与运行状态的纯数据模型 —— 不含任何 AWT/Swing 类型。
 *
 * <p>顶栏计数、三栏布局、表格列、详情分节、托盘菜单、状态文案、响应式档位等信息全部沉淀在这里，
 * 因此 headless 环境下可以完全脱离窗口断言整个组件树结构与关键文案。
 *
 * <p>运行统计通过 {@link Listener} 回调刷新（服务层推送，不轮询数据库）；
 * 模型可用性变化时同步更新「归因未生成」横幅。
 */
public final class UiModel {

    /** 模型不可达时的横幅文案。 */
    public static final String BANNER_NO_MODEL = "归因未生成";

    /** 服务层统计与模型可用性回调。 */
    public interface Listener {
        void onCountersChanged(Map<String, Integer> counters);

        void onModelAvailabilityChanged(boolean available, String banner);
    }

    private final List<String> panels;
    private final List<String> tableColumns;
    private final List<String> detailSections;
    private final List<String> trayMenu;
    private final List<String> toastKinds;
    private final List<String> confirmableActions;
    private final List<Integer> splitRatios;
    private final List<String> viewTreeNodes;
    /** 中央影响面表的真实数据行（纯字符串矩阵，与 tableColumns 列序一致）。 */
    private final List<List<String>> tableRows;
    /** 右详情抽屉的文本（证据链逐跳回溯，真实分析结果生成）。 */
    private final String detailText;
    private final String loadingText;
    private final String emptyText;
    private final String errorText;

    private final Map<String, Integer> topCounters = new LinkedHashMap<>();
    private String statusText;
    private boolean writeSwitchOn;
    private boolean modelAvailable;
    private String banner;

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private UiModel(Builder builder) {
        this.panels = List.copyOf(builder.panels);
        this.tableColumns = List.copyOf(builder.tableColumns);
        this.detailSections = List.copyOf(builder.detailSections);
        this.trayMenu = List.copyOf(builder.trayMenu);
        this.toastKinds = List.copyOf(builder.toastKinds);
        this.confirmableActions = List.copyOf(builder.confirmableActions);
        this.splitRatios = List.copyOf(builder.splitRatios);
        this.viewTreeNodes = List.copyOf(builder.viewTreeNodes);
        List<List<String>> rows = new ArrayList<>();
        for (List<String> row : builder.tableRows) {
            rows.add(row == null ? List.of() : List.copyOf(row));
        }
        this.tableRows = List.copyOf(rows);
        this.detailText = builder.detailText;
        this.loadingText = builder.loadingText;
        this.emptyText = builder.emptyText;
        this.errorText = builder.errorText;
        this.topCounters.putAll(builder.topCounters);
        this.statusText = builder.statusText;
        this.writeSwitchOn = builder.writeSwitchOn;
        this.modelAvailable = builder.modelAvailable;
        this.banner = builder.banner;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ---- 布局结构（纯数据，可 headless 断言）----

    /** 顶栏 / 左视图树 / 中央影响面表 / 右详情抽屉 / 底部状态条。 */
    public List<String> panels() {
        return panels;
    }

    /** 顶栏三个大数字：解析 / 计算 / 失败。 */
    public Map<String, Integer> topCounters() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(topCounters));
    }

    public String statusText() {
        return statusText;
    }

    public boolean writeSwitchOn() {
        return writeSwitchOn;
    }

    public boolean modelAvailable() {
        return modelAvailable;
    }

    /** 模型不可达时为「归因未生成」，否则为空。 */
    public String banner() {
        return banner;
    }

    /** 中央影响面表列：分区/字段/来源列/判定/置信度/口径/跳数/批次。 */
    public List<String> tableColumns() {
        return tableColumns;
    }

    /** 右详情抽屉分节。 */
    public List<String> detailSections() {
        return detailSections;
    }

    /** 托盘菜单项。 */
    public List<String> trayMenu() {
        return trayMenu;
    }

    public List<Integer> splitRatios() {
        return splitRatios;
    }

    /** 左视图树节点：按视图名分组，标注摘要与「已过期」。 */
    public List<String> viewTreeNodes() {
        return viewTreeNodes;
    }

    /** 影响面表真实数据行；空列表表示尚未载入分析结果的空态。 */
    public List<List<String>> tableRows() {
        return tableRows;
    }

    /** 右详情抽屉文本；空串表示尚未选中影响面行。 */
    public String detailText() {
        return detailText;
    }

    public String loadingText() {
        return loadingText;
    }

    public String emptyText() {
        return emptyText;
    }

    public String errorText() {
        return errorText;
    }

    public List<String> toastKinds() {
        return toastKinds;
    }

    public List<String> confirmableActions() {
        return confirmableActions;
    }

    /** 1280 档三栏全展开，768 档紧凑布局。 */
    public String layoutMode(int width) {
        return width >= 1024 ? "three-column" : "compact";
    }

    public boolean leftTreeCollapsed(int width) {
        return "compact".equals(layoutMode(width));
    }

    public boolean detailAsOverlay(int width) {
        return "compact".equals(layoutMode(width));
    }

    /** 两档宽度下均不出现横向滚动条。 */
    public boolean horizontalScrollBar(int width) {
        return false;
    }

    // ---- 运行统计回调（服务层推送，不轮询数据库）----

    public void addListener(Listener listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void updateCounters(int parsed, int computed, int failed) {
        topCounters.put("解析", parsed);
        topCounters.put("计算", computed);
        topCounters.put("失败", failed);
        Map<String, Integer> snapshot = Collections.unmodifiableMap(new LinkedHashMap<>(topCounters));
        for (Listener listener : listeners) {
            listener.onCountersChanged(snapshot);
        }
    }

    public void setModelAvailable(boolean available) {
        this.modelAvailable = available;
        this.banner = available ? "" : BANNER_NO_MODEL;
        for (Listener listener : listeners) {
            listener.onModelAvailabilityChanged(available, banner);
        }
    }

    public void setStatusText(String text) {
        this.statusText = text == null ? "" : text;
    }

    public void setWriteSwitchOn(boolean on) {
        this.writeSwitchOn = on;
    }

    /** {@link UiModel} 构造器：给出开箱即用的默认工作台布局，亦可逐项覆盖。 */
    public static final class Builder {

        private List<String> panels = new ArrayList<>(List.of(
                "top-bar", "view-tree", "impact-table", "detail-drawer", "status-bar"));
        private Map<String, Integer> topCounters = defaultCounters();
        private String statusText = "阶段：就绪 ｜ 耗时：0ms ｜ 降级：无";
        private boolean writeSwitchOn;
        private boolean modelAvailable = true;
        private String banner = "";
        private List<String> tableColumns = new ArrayList<>(List.of(
                "分区", "字段", "来源列", "判定", "置信度", "口径", "跳数", "批次"));
        private List<String> detailSections = new ArrayList<>(List.of(
                "证据链逐跳", "口径与置信度", "判定呈现", "待人工核对条目", "需人工裁决入口"));
        private List<String> trayMenu = new ArrayList<>(List.of(
                "打开主窗口", "立即重算", "导出变更单", "退出"));
        private String loadingText = "加载态：骨架行占位 + 进度提示，正在计算影响面…";
        private String emptyText = "空态：尚未选择视图，请从左侧选择取数视图；或点击「载入样例」导入样例视图。";
        private String errorText = "错误态：加载失败（只读库或模型端点不可达）。建议：检查配置后点击「重试」，或查看状态条留痕。";
        private List<String> toastKinds = new ArrayList<>(List.of("成功", "警告", "失败"));
        private List<String> confirmableActions = new ArrayList<>(List.of(
                "撤回人工确认", "开启写类开关", "批量标记为已确认"));
        private List<Integer> splitRatios = new ArrayList<>(List.of(18, 56, 26));
        private List<List<String>> tableRows = new ArrayList<>();
        private String detailText = "";
        private List<String> viewTreeNodes = new ArrayList<>(List.of(
                "V_BILL_FEE（摘要 a1b2c3d4 · 已过期）",
                "V_BILL_FEE_AGG（摘要 b2c3d4e5）",
                "V_PATIENT_BASIC（摘要 c3d4e5f6 · 已过期）",
                "V_INPATIENT_DIAG（摘要 d4e5f6a7）"));

        private Builder() {
        }

        private static Map<String, Integer> defaultCounters() {
            Map<String, Integer> counters = new LinkedHashMap<>();
            counters.put("解析", 0);
            counters.put("计算", 0);
            counters.put("失败", 0);
            return counters;
        }

        public Builder panels(List<String> value) {
            this.panels = copyOf(value);
            return this;
        }

        public Builder topCounters(Map<String, Integer> value) {
            this.topCounters = value == null ? defaultCounters() : new LinkedHashMap<>(value);
            return this;
        }

        public Builder statusText(String value) {
            this.statusText = value == null ? "" : value;
            return this;
        }

        public Builder writeSwitchOn(boolean value) {
            this.writeSwitchOn = value;
            return this;
        }

        public Builder modelAvailable(boolean value) {
            this.modelAvailable = value;
            return this;
        }

        public Builder banner(String value) {
            this.banner = value == null ? "" : value;
            return this;
        }

        public Builder tableColumns(List<String> value) {
            this.tableColumns = copyOf(value);
            return this;
        }

        public Builder detailSections(List<String> value) {
            this.detailSections = copyOf(value);
            return this;
        }

        public Builder trayMenu(List<String> value) {
            this.trayMenu = copyOf(value);
            return this;
        }

        public Builder loadingText(String value) {
            this.loadingText = value == null ? "" : value;
            return this;
        }

        public Builder emptyText(String value) {
            this.emptyText = value == null ? "" : value;
            return this;
        }

        public Builder errorText(String value) {
            this.errorText = value == null ? "" : value;
            return this;
        }

        public Builder toastKinds(List<String> value) {
            this.toastKinds = copyOf(value);
            return this;
        }

        public Builder confirmableActions(List<String> value) {
            this.confirmableActions = copyOf(value);
            return this;
        }

        public Builder splitRatios(List<Integer> value) {
            this.splitRatios = copyOf(value);
            return this;
        }

        public Builder viewTreeNodes(List<String> value) {
            this.viewTreeNodes = copyOf(value);
            return this;
        }

        public Builder tableRows(List<List<String>> value) {
            this.tableRows = value == null ? new ArrayList<>() : new ArrayList<>(value);
            return this;
        }

        public Builder detailText(String value) {
            this.detailText = value == null ? "" : value;
            return this;
        }

        private static <T> List<T> copyOf(List<T> value) {
            return value == null ? new ArrayList<>() : new ArrayList<>(value);
        }

        public UiModel build() {
            return new UiModel(this);
        }
    }
}
