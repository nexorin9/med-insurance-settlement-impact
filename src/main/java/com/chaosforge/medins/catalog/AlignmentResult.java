package com.chaosforge.medins.catalog;

import com.chaosforge.medins.model.ListField;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 字段对齐结果 —— 「一个取数列」对齐到「若干栏结算清单字段」的结论。
 *
 * <p>按命中来源分成四类（{@link Group}）：列名精确 / 别名 / 交模型判定 / 歧义待人工裁决。
 * 命中轨迹 {@link #hitTrail()} 逐级记录「哪一级命中、哪一级未命中」，不隐藏降级来源，
 * 便于 UI 与导出解释「为什么这一列要交给模型」或「为什么被挂起」。
 *
 * <p>规则：任一级出现一对多歧义（某列对多个清单字段、或多列对同一字段）即拒绝自动合并，
 * 一律标 {@link Group#AMBIGUOUS} 进待人工裁决清单。同名不同分区的字段各自携带分区，
 * 不会因为名字相同被误合并成一条。
 */
public final class AlignmentResult {

    /** 对齐结果四类分组；顺序即呈现优先级：精确 → 别名 → 模型 → 歧义。 */
    public enum Group {
        EXACT,
        ALIAS,
        MODEL,
        AMBIGUOUS
    }

    private final String sourceColumn;
    private final Group group;
    private final List<ListField> matches;
    private final boolean ambiguous;
    private final List<String> hitTrail;
    private final String reasonZh;

    public AlignmentResult(String sourceColumn,
                           Group group,
                           List<ListField> matches,
                           boolean ambiguous,
                           List<String> hitTrail,
                           String reasonZh) {
        this.sourceColumn = sourceColumn == null ? "" : sourceColumn.trim();
        this.group = Objects.requireNonNull(group, "group 不可为 null");
        this.matches = matches == null ? List.of() : List.copyOf(matches);
        this.ambiguous = ambiguous;
        this.hitTrail = hitTrail == null ? List.of() : List.copyOf(hitTrail);
        this.reasonZh = reasonZh == null ? "" : reasonZh;
    }

    /** 命中分组。 */
    public Group group() {
        return group;
    }

    /** 命中的清单字段列表；MODEL 级为空（由归因模块再判）。 */
    public List<ListField> matches() {
        return matches;
    }

    /** 是否一对多歧义（等价于分组为 AMBIGUOUS）。 */
    public boolean ambiguous() {
        return ambiguous;
    }

    /** 被对齐的来源列名。 */
    public String sourceColumn() {
        return sourceColumn;
    }

    /** 三级命中轨迹，逐级说明命中/未命中原因。 */
    public List<String> hitTrail() {
        return hitTrail;
    }

    /** 中文原因（歧义或降级时给出可核对的说明）。 */
    public String reasonZh() {
        return reasonZh;
    }

    // ---------- 便于 FieldAligner 构造的静态工厂 ----------

    static AlignmentResult exact(String column, ListField field, List<String> trail) {
        return new AlignmentResult(column, Group.EXACT, List.of(field), false, trail,
                "列名精确命中，确定性基线成立，无需询问模型");
    }

    static AlignmentResult alias(String column, ListField field, List<String> trail) {
        return new AlignmentResult(column, Group.ALIAS, List.of(field), false, trail,
                "内置别名表命中，优先于模型判定");
    }

    static AlignmentResult model(String column, List<String> trail) {
        return new AlignmentResult(column, Group.MODEL, List.of(), false, trail,
                "列名与别名均未命中，词面不一致，交归因模块模型判定");
    }

    static AlignmentResult ambiguous(String column, List<ListField> candidates,
                                     List<String> trail, String reasonZh) {
        return new AlignmentResult(column, Group.AMBIGUOUS, candidates, true, trail, reasonZh);
    }

    /** 把一批对齐结果按四类分组输出；四类键都会出现（即使对应结果为空）。 */
    public static Map<Group, List<AlignmentResult>> groupBy(List<AlignmentResult> results) {
        Map<Group, List<AlignmentResult>> grouped = new EnumMap<>(Group.class);
        for (Group g : Group.values()) {
            grouped.put(g, new ArrayList<>());
        }
        if (results != null) {
            for (AlignmentResult r : results) {
                grouped.get(r.group()).add(r);
            }
        }
        Map<Group, List<AlignmentResult>> frozen = new EnumMap<>(Group.class);
        for (Map.Entry<Group, List<AlignmentResult>> e : grouped.entrySet()) {
            frozen.put(e.getKey(), Collections.unmodifiableList(e.getValue()));
        }
        return Collections.unmodifiableMap(frozen);
    }

    @Override
    public String toString() {
        return "AlignmentResult{" + sourceColumn + " -> " + group
                + ", 命中=" + matches.size() + ", 歧义=" + ambiguous + "}";
    }
}
