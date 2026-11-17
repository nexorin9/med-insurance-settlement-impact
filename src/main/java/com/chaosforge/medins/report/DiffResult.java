package com.chaosforge.medins.report;

import com.chaosforge.medins.model.ImpactRecord;

import java.util.List;

/**
 * 两版影响面差异结果：五类差异分组保存，可导出为 CSV 与中文说明供内审逐条核对。
 * 全部为纯数据，不依赖任何 UI 组件。
 */
public final class DiffResult {

    /** 差异类别。 */
    public enum Kind {
        ADDED("新增"),
        REMOVED("消失"),
        VERDICT_CHANGED("判定变化"),
        SCOPE_CHANGED("口径变化"),
        SOURCE_CHANGED("来源变化");

        private final String zh;

        Kind(String zh) {
            this.zh = zh;
        }

        public String zh() {
            return zh;
        }
    }

    private final List<Entry> added;
    private final List<Entry> removed;
    private final List<Entry> verdictChanged;
    private final List<Entry> scopeChanged;
    private final List<Entry> sourceChanged;

    DiffResult(List<Entry> added, List<Entry> removed, List<Entry> verdictChanged,
               List<Entry> scopeChanged, List<Entry> sourceChanged) {
        this.added = List.copyOf(added);
        this.removed = List.copyOf(removed);
        this.verdictChanged = List.copyOf(verdictChanged);
        this.scopeChanged = List.copyOf(scopeChanged);
        this.sourceChanged = List.copyOf(sourceChanged);
    }

    /** 新版新增的受影响字段。 */
    public List<Entry> added() {
        return added;
    }

    /** 新版消失的受影响字段。 */
    public List<Entry> removed() {
        return removed;
    }

    /** 同一清单字段的归因判定发生变化。 */
    public List<Entry> verdictChanged() {
        return verdictChanged;
    }

    /** 同一清单字段的计算口径发生变化。 */
    public List<Entry> scopeChanged() {
        return scopeChanged;
    }

    /** 同一清单字段的来源列改名（单独归类，不算新增/消失）。 */
    public List<Entry> sourceChanged() {
        return sourceChanged;
    }

    public int total() {
        return added.size() + removed.size() + verdictChanged.size()
                + scopeChanged.size() + sourceChanged.size();
    }

    /** 导出为 CSV：差异类型,分区,字段,变化前,变化后。 */
    public String toCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("差异类型,分区,字段,变化前,变化后\r\n");
        appendRows(sb, added);
        appendRows(sb, removed);
        appendRows(sb, verdictChanged);
        appendRows(sb, scopeChanged);
        appendRows(sb, sourceChanged);
        return sb.toString();
    }

    private static void appendRows(StringBuilder sb, List<Entry> rows) {
        for (Entry entry : rows) {
            sb.append(entry.toCsvRow()).append("\r\n");
        }
    }

    /** 导出为中文说明，供内审抽查逐条核对。 */
    public String toZh() {
        StringBuilder sb = new StringBuilder();
        sb.append("# 两版取数视图影响面差异回放（供内审核对 · 草稿）\n\n");
        appendSection(sb, "新增受影响字段", added);
        appendSection(sb, "消失的受影响字段", removed);
        appendSection(sb, "判定变化", verdictChanged);
        appendSection(sb, "口径变化", scopeChanged);
        appendSection(sb, "来源变化（来源列改名）", sourceChanged);
        sb.append("> 仅含字段级关系，不含患者明细、真实批次号与凭据。\n");
        return sb.toString();
    }

    private static void appendSection(StringBuilder sb, String title, List<Entry> rows) {
        sb.append("## ").append(title).append("：").append(rows.size()).append(" 条\n");
        if (rows.isEmpty()) {
            sb.append("- 无\n\n");
            return;
        }
        for (Entry entry : rows) {
            sb.append("- ").append(entry.toZh()).append("\n");
        }
        sb.append("\n");
    }

    /** 单条差异（纯数据）。 */
    public static final class Entry {

        private final Kind kind;
        private final String section;
        private final String fieldName;
        private final String before;
        private final String after;

        private Entry(Kind kind, String section, String fieldName, String before, String after) {
            this.kind = kind;
            this.section = section;
            this.fieldName = fieldName;
            this.before = before;
            this.after = after;
        }

        static Entry of(Kind kind, ImpactRecord older, ImpactRecord newer, String before, String after) {
            ImpactRecord reference = newer != null ? newer : older;
            return new Entry(kind, reference.section(), reference.listFieldName(), before, after);
        }

        public Kind kind() {
            return kind;
        }

        public String section() {
            return section;
        }

        public String fieldName() {
            return fieldName;
        }

        public String uniqueKey() {
            return ReportText.fieldKey(section, fieldName);
        }

        /** 变化前摘要。 */
        public String before() {
            return before;
        }

        /** 变化后摘要。 */
        public String after() {
            return after;
        }

        public String toCsvRow() {
            return ReportText.escapeCsv(kind.zh()) + ","
                    + ReportText.escapeCsv(section) + ","
                    + ReportText.escapeCsv(fieldName) + ","
                    + ReportText.escapeCsv(before) + ","
                    + ReportText.escapeCsv(after);
        }

        public String toZh() {
            return section + " / " + fieldName + "（" + kind.zh() + "）：" + before + " → " + after;
        }
    }
}
