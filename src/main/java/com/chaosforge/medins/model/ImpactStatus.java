package com.chaosforge.medins.model;

/**
 * 影响面记录的业务状态。
 *
 * <p>主链：{@code PARSED → COMPUTED → ATTRIBUTED → PENDING_CONFIRM → CONFIRMED → WRITTEN_BACK}；
 * 旁支：{@link #DEGRADED}（模型不可达，归因未生成）、{@link #AMBIGUOUS}（一对多歧义挂起待裁决）、
 * {@link #PENDING_WRITEBACK}（回写失败待重试）。
 *
 * <p>状态跃迁的合法性由 {@code service} 模块的状态机裁决：{@code CONFIRMED} 只能由**人工确认动作**推进，
 * 模型与后台任务都无权推进。
 */
public enum ImpactStatus {

    PARSED("parsed", "已解析"),
    COMPUTED("computed", "已算出影响面"),
    ATTRIBUTED("attributed", "已归因"),
    PENDING_CONFIRM("pending_confirm", "待人工确认"),
    CONFIRMED("confirmed", "已人工确认"),
    WRITTEN_BACK("written_back", "已回写台账"),
    DEGRADED("degraded", "已降级"),
    AMBIGUOUS("ambiguous", "歧义待裁决"),
    PENDING_WRITEBACK("pending_writeback", "待回写");

    private final String wireName;
    private final String labelZh;

    ImpactStatus(String wireName, String labelZh) {
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

    /** 是否已由人工确认（只有此处为真才允许构造回写请求）。 */
    public boolean isHumanConfirmed() {
        return this == CONFIRMED || this == WRITTEN_BACK;
    }

    /**
     * 按报文取值解析；无法识别时返回 {@code null}，由调用方显式报错，不静默兜底。
     */
    public static ImpactStatus fromWireName(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        for (ImpactStatus status : values()) {
            if (status.wireName.equalsIgnoreCase(trimmed)) {
                return status;
            }
        }
        return null;
    }
}
