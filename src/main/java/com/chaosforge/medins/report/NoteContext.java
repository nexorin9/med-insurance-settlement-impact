package com.chaosforge.medins.report;

import com.chaosforge.medins.model.ChangeKind;
import com.chaosforge.medins.model.ImpactScope;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 生成厂商说明所需的上下文（纯数据，不含 AWT/Swing 类型）。
 *
 * <p>证据链以 {@code Map<String,String>} 传入：键为清单字段名（或「分区+字段名」唯一键），
 * 值为证据链中文短句。说明生成器只转述这些短句与影响记录里的归因理由，不重新编造措辞。
 */
public final class NoteContext {

    private final String viewName;
    private final String changedField;
    private final ChangeKind changeKind;
    private final String changeNoteZh;
    private final String sqlDigest;
    private final ImpactScope scope;
    private final boolean degraded;
    private final Map<String, String> evidence;

    private NoteContext(Builder builder) {
        this.viewName = builder.viewName;
        this.changedField = builder.changedField;
        this.changeKind = builder.changeKind;
        this.changeNoteZh = builder.changeNoteZh;
        this.sqlDigest = builder.sqlDigest;
        this.scope = builder.scope;
        this.degraded = builder.degraded;
        this.evidence = Collections.unmodifiableMap(new LinkedHashMap<>(builder.evidence));
    }

    public static Builder builder() {
        return new Builder();
    }

    public String viewName() {
        return viewName;
    }

    public String changedField() {
        return changedField;
    }

    public ChangeKind changeKind() {
        return changeKind;
    }

    public String changeNoteZh() {
        return changeNoteZh;
    }

    public String sqlDigest() {
        return sqlDigest;
    }

    public ImpactScope scope() {
        return scope;
    }

    /** 模型是否不可达（归因未生成、禁止回写）。 */
    public boolean degraded() {
        return degraded;
    }

    /** 清单字段（或唯一键）到证据链中文短句的映射。 */
    public Map<String, String> evidence() {
        return evidence;
    }

    /** {@link NoteContext} 的构造器，字段与契约 §3.5 一致。 */
    public static final class Builder {

        private String viewName = "";
        private String changedField = "";
        private ChangeKind changeKind = ChangeKind.LOGIC_CHANGED;
        private String changeNoteZh = "";
        private String sqlDigest = "";
        private ImpactScope scope = ImpactScope.STRICT;
        private boolean degraded;
        private Map<String, String> evidence = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder viewName(String value) {
            this.viewName = value == null ? "" : value;
            return this;
        }

        public Builder changedField(String value) {
            this.changedField = value == null ? "" : value;
            return this;
        }

        public Builder changeKind(ChangeKind value) {
            this.changeKind = value == null ? ChangeKind.LOGIC_CHANGED : value;
            return this;
        }

        public Builder changeNoteZh(String value) {
            this.changeNoteZh = value == null ? "" : value;
            return this;
        }

        public Builder sqlDigest(String value) {
            this.sqlDigest = value == null ? "" : value;
            return this;
        }

        public Builder scope(ImpactScope value) {
            this.scope = value == null ? ImpactScope.STRICT : value;
            return this;
        }

        public Builder degraded(boolean value) {
            this.degraded = value;
            return this;
        }

        public Builder evidence(Map<String, String> value) {
            this.evidence = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
            return this;
        }

        /** 便捷方法：逐条登记证据链短句。 */
        public Builder putEvidence(String listField, String evidenceZh) {
            this.evidence.put(listField, evidenceZh);
            return this;
        }

        public NoteContext build() {
            return new NoteContext(this);
        }
    }
}
