package com.chaosforge.medins.service;

import com.chaosforge.medins.attribution.ModelGateway;
import com.chaosforge.medins.model.ChangeKind;
import com.chaosforge.medins.model.ImpactScope;

import java.nio.file.Path;

/** 影响面分析输入：一次「改动点」分析所需的全部上下文。 */
public final class AnalysisInput {

    private final String viewName;
    private final String sql;
    private final String changedField;
    private final ChangeKind changeKind;
    private final String changeNoteZh;
    private final Path dictionaryPath;
    private final ImpactScope scope;
    private final boolean writeSwitchOn;
    private final String operator;
    private final boolean permissionGranted;
    private final ModelGateway modelGateway;

    private AnalysisInput(Builder b) {
        this.viewName = b.viewName == null ? "" : b.viewName;
        this.sql = b.sql == null ? "" : b.sql;
        this.changedField = b.changedField == null ? "" : b.changedField;
        this.changeKind = b.changeKind == null ? ChangeKind.LOGIC_CHANGED : b.changeKind;
        this.changeNoteZh = b.changeNoteZh == null ? "" : b.changeNoteZh;
        this.dictionaryPath = b.dictionaryPath;
        this.scope = b.scope == null ? ImpactScope.STRICT : b.scope;
        this.writeSwitchOn = b.writeSwitchOn;
        this.operator = b.operator == null ? "" : b.operator;
        this.permissionGranted = b.permissionGranted;
        this.modelGateway = b.modelGateway;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String viewName() { return viewName; }
    public String sql() { return sql; }
    public String changedField() { return changedField; }
    public ChangeKind changeKind() { return changeKind; }
    public String changeNoteZh() { return changeNoteZh; }
    public Path dictionaryPath() { return dictionaryPath; }
    public ImpactScope scope() { return scope; }
    public boolean writeSwitchOn() { return writeSwitchOn; }
    public String operator() { return operator; }
    public boolean permissionGranted() { return permissionGranted; }
    /** 可注入的模型网关；null 表示走确定性归因（列名精确匹配，不调模型）。 */
    public ModelGateway modelGateway() { return modelGateway; }

    public static final class Builder {
        private String viewName = "";
        private String sql = "";
        private String changedField = "";
        private ChangeKind changeKind = ChangeKind.LOGIC_CHANGED;
        private String changeNoteZh = "";
        private Path dictionaryPath;
        private ImpactScope scope = ImpactScope.STRICT;
        private boolean writeSwitchOn = false;   // 写类开关默认关
        private String operator = "";
        private boolean permissionGranted = false; // 权限默认不具备
        private ModelGateway modelGateway;

        private Builder() { }

        public Builder viewName(String v) { this.viewName = v; return this; }
        public Builder sql(String v) { this.sql = v; return this; }
        public Builder changedField(String v) { this.changedField = v; return this; }
        public Builder changeKind(ChangeKind v) { this.changeKind = v; return this; }
        public Builder changeNoteZh(String v) { this.changeNoteZh = v; return this; }
        public Builder dictionaryPath(Path v) { this.dictionaryPath = v; return this; }
        public Builder scope(ImpactScope v) { this.scope = v; return this; }
        public Builder writeSwitchOn(boolean v) { this.writeSwitchOn = v; return this; }
        public Builder operator(String v) { this.operator = v; return this; }
        public Builder permissionGranted(boolean v) { this.permissionGranted = v; return this; }
        public Builder modelGateway(ModelGateway v) { this.modelGateway = v; return this; }

        public AnalysisInput build() {
            if (viewName == null || viewName.isBlank()) {
                throw new IllegalArgumentException("分析输入必须带视图名");
            }
            if (changedField == null || changedField.isBlank()) {
                throw new IllegalArgumentException("分析输入必须带被改动的列名");
            }
            return new AnalysisInput(this);
        }
    }
}
