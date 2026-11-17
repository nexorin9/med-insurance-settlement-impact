package com.chaosforge.medins.sqlparse;

import java.util.ArrayList;
import java.util.List;

/**
 * UNION 分支归并决策：比较各分支投影列数是否一致，一致则把各分支同位置输出列
 * 合并到同一输出列名（取第一分支），不一致则判定冲突、给出可定位的提示，绝不静默只取其一。
 *
 * <p>契约 §4 / Task 8：列数一致可合并，边带 branchIndex；列数不一致登记
 * {@code UNION_BRANCH_CONFLICT} 且两分支的边都要保留（不得只取其一冒充全部）。
 */
public final class UnionBranchMerger {

    private UnionBranchMerger() { }

    /** 归并结论。 */
    public static final class Verdict {
        public final boolean consistent;             // 各分支列数一致
        public final boolean conflict;               // 多分支且列数不一致
        public final String hint;                    // 冲突说明（含各分支列数）
        public final List<Integer> columnCounts;    // 各分支列数
        public final List<List<String>> outputColumns;      // 各分支按位置的列名
        public final List<String> mergedOutputColumns;     // 一致时的统一输出列名

        Verdict(boolean consistent, boolean conflict, String hint,
                List<Integer> columnCounts, List<List<String>> outputColumns,
                List<String> mergedOutputColumns) {
            this.consistent = consistent;
            this.conflict = conflict;
            this.hint = hint;
            this.columnCounts = List.copyOf(columnCounts);
            this.outputColumns = List.copyOf(outputColumns);
            this.mergedOutputColumns = List.copyOf(mergedOutputColumns);
        }
    }

    public static Verdict evaluate(List<Integer> columnCounts, List<List<String>> outputColumns) {
        List<Integer> counts = columnCounts == null ? List.of() : new ArrayList<>(columnCounts);
        List<List<String>> cols = outputColumns == null ? List.of() : new ArrayList<>(outputColumns);

        boolean consistent = !counts.isEmpty();
        if (consistent) {
            int first = counts.get(0);
            for (int c : counts) if (c != first) { consistent = false; break; }
        }
        boolean conflict = counts.size() > 1 && !consistent;

        String hint = "";
        if (conflict) {
            StringBuilder sb = new StringBuilder("UNION 分支列数不一致，不能安全合并：");
            for (int i = 0; i < counts.size(); i++) {
                if (i > 0) sb.append("，");
                sb.append("第 ").append(i + 1).append(" 分支 ").append(counts.get(i)).append(" 列");
            }
            sb.append("；各分支边已分别保留，请人工核对分支列对齐关系。");
            hint = sb.toString();
        }
        List<String> merged = consistent && !cols.isEmpty() ? cols.get(0) : List.of();
        return new Verdict(consistent, conflict, hint, counts, cols, merged);
    }

    /** 一致时按位置返回统一输出列名；冲突或越界时退回本分支自身列名。 */
    public static String mergedColumnName(Verdict v, int branchIndex, int position, String ownName) {
        if (v != null && v.consistent && position >= 0 && position < v.mergedOutputColumns.size()) {
            return v.mergedOutputColumns.get(position);
        }
        return ownName;
    }
}
