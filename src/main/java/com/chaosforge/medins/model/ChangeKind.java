package com.chaosforge.medins.model;

/**
 * 人工改动说明的类型 —— 专员在工具里登记「这一列被怎么动了」。
 *
 * <p>取值与 spec.md §2.3 一致；{@code change_note_zh}（≤200 字）由改动说明携带，
 * 供模型做语义归因（是否同名同义），确定性层只消费本枚举。
 */
public enum ChangeKind {

    RENAMED("renamed", "改名"),
    LOGIC_CHANGED("logic_changed", "逻辑变更"),
    FILTER_ADDED("filter_added", "新增筛选条件"),
    DROPPED("dropped", "删除"),
    ADDED("added", "新增");

    private final String wireName;
    private final String labelZh;

    ChangeKind(String wireName, String labelZh) {
        this.wireName = wireName;
        this.labelZh = labelZh;
    }

    /** 落库、导出与接口报文中的取值。 */
    public String wireName() {
        return wireName;
    }

    /** 中文界面标签。 */
    public String labelZh() {
        return labelZh;
    }

    /**
     * 按报文取值解析；无法识别时返回 {@code null}，由调用方显式报错，不静默兜底。
     */
    public static ChangeKind fromWireName(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        for (ChangeKind kind : values()) {
            if (kind.wireName.equalsIgnoreCase(trimmed)) {
                return kind;
            }
        }
        return null;
    }
}
