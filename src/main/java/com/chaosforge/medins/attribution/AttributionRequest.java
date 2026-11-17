package com.chaosforge.medins.attribution;

import com.chaosforge.medins.model.ChangeKind;

import java.util.List;

/**
 * 归因请求 —— 确定性层候选边结构化子图 + 人工改动说明。
 *
 * <p>请求内容只含结构信息（视图、改动类型、改动说明、候选边），不含任何患者可识别明细，
 * 也不含 API Key 等凭据。业务层只通过 {@link ModelGateway} 接口传递本对象。
 */
public final class AttributionRequest {

    private final String viewName;
    private final ChangeKind changeKind;
    private final String changeNoteZh;
    private final List<CandidateEdge> candidates;

    private AttributionRequest(Builder b) {
        this.viewName = b.viewName == null ? "" : b.viewName;
        this.changeKind = b.changeKind;
        this.changeNoteZh = b.changeNoteZh == null ? "" : b.changeNoteZh;
        this.candidates = b.candidates == null ? List.of() : List.copyOf(b.candidates);
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<CandidateEdge> candidates() {
        return candidates;
    }

    public ChangeKind changeKind() {
        return changeKind;
    }

    public String changeNoteZh() {
        return changeNoteZh;
    }

    public String viewName() {
        return viewName;
    }

    /** 复制构造器：确定性基线过滤候选边时重建请求用。 */
    public Builder toBuilder() {
        return builder()
                .viewName(viewName)
                .changeKind(changeKind)
                .changeNoteZh(changeNoteZh)
                .candidates(candidates);
    }

    /** {@link AttributionRequest} 构造器。 */
    public static final class Builder {
        private String viewName = "";
        private ChangeKind changeKind;
        private String changeNoteZh = "";
        private List<CandidateEdge> candidates = List.of();

        private Builder() {
        }

        public Builder viewName(String value) {
            this.viewName = value;
            return this;
        }

        public Builder changeKind(ChangeKind value) {
            this.changeKind = value;
            return this;
        }

        public Builder changeNoteZh(String value) {
            this.changeNoteZh = value;
            return this;
        }

        public Builder candidates(List<CandidateEdge> value) {
            this.candidates = value;
            return this;
        }

        public AttributionRequest build() {
            return new AttributionRequest(this);
        }
    }
}
