package com.chaosforge.medins.catalog;

import com.chaosforge.medins.model.ListField;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 字段 ↔ 取数列三级对齐器。
 *
 * <p>清单字段名（金额合计）与取数列名（FEE_AMT）常不共享词面，按固定三级顺序对齐：
 * <ol>
 *   <li><b>列名精确</b>：来源列归一后与清单字段名/字段代码词面一致；</li>
 *   <li><b>别名表</b>：内置映射（键为小写归一列名，如 {@code fee_amt → 金额合计}、
 *       {@code patient_id → 医保个人编号}），命中优先于模型判定；</li>
 *   <li><b>模型判定</b>：前两级都未命中，词面不一致，交归因模块模型判断同义与否。</li>
 * </ol>
 * 每一级命中情况写入 {@link AlignmentResult#hitTrail()}，不隐藏降级来源。
 *
 * <p>一对多歧义一律拒绝自动合并：某列能对上多个清单字段（含同名不同分区），
 * 或多个列能对上同一清单字段，均标 {@link AlignmentResult.Group#AMBIGUOUS} 进待人工裁决。
 * 同名不同分区的字段各自携带分区信息，不会因为名字相同被误合并。
 */
public final class FieldAligner {

    private final Map<String, List<String>> displayAliases;
    private final Map<String, List<String>> lookup;

    public FieldAligner() {
        this(builtinAliases());
    }

    /** 注入自定义别名表（测试与院内扩展用）；键会按小写归一登记。 */
    public FieldAligner(Map<String, List<String>> aliases) {
        Map<String, List<String>> display = new LinkedHashMap<>();
        Map<String, List<String>> byNorm = new LinkedHashMap<>();
        if (aliases != null) {
            for (Map.Entry<String, List<String>> e : aliases.entrySet()) {
                String key = e.getKey() == null ? "" : e.getKey().trim().toLowerCase(Locale.ROOT);
                List<String> vals = e.getValue() == null ? List.of() : List.copyOf(e.getValue());
                display.put(key, vals);
                String nk = norm(key);
                if (!nk.isEmpty()) {
                    byNorm.computeIfAbsent(nk, k -> new ArrayList<>()).addAll(vals);
                }
            }
        }
        this.displayAliases = Collections.unmodifiableMap(display);
        Map<String, List<String>> frozen = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : byNorm.entrySet()) {
            frozen.put(e.getKey(), List.copyOf(e.getValue()));
        }
        this.lookup = Collections.unmodifiableMap(frozen);
    }

    /** 内置别名表：键为小写列名，值为清单字段定位线索（字段名/唯一子串/分区斜杠字段名）。 */
    public Map<String, List<String>> aliasTable() {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : displayAliases.entrySet()) {
            copy.put(e.getKey(), List.copyOf(e.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    /** 单个来源列的三级对齐。 */
    public AlignmentResult align(String sourceColumn, ListFieldDictionary dict) {
        if (dict == null) {
            throw new IllegalArgumentException("对齐所用字典不可为 null");
        }
        String column = sourceColumn == null ? "" : sourceColumn.trim();
        List<String> trail = new ArrayList<>();
        String key = norm(column);

        // ① 列名精确
        Map<String, ListField> exactHits = new LinkedHashMap<>();
        if (!key.isEmpty()) {
            for (ListField f : dict.fields()) {
                String fn = norm(f.fieldName());
                String fc = norm(f.fieldCode());
                if (key.equals(fn) || (!fc.isEmpty() && key.equals(fc))) {
                    exactHits.putIfAbsent(f.uniqueKey(), f);
                }
            }
        }
        trail.add("① 列名精确：" + (exactHits.isEmpty() ? "未命中" : "命中 " + exactHits.size() + " 个"));
        if (exactHits.size() == 1) {
            return AlignmentResult.exact(column, exactHits.values().iterator().next(), trail);
        }
        if (exactHits.size() > 1) {
            return AlignmentResult.ambiguous(column, new ArrayList<>(exactHits.values()), trail,
                    "列名精确匹配同时命中多个清单字段（可能同名不同分区），一对多歧义，拒绝自动合并");
        }

        // ② 别名表
        List<String> clues = lookup.get(key);
        if (clues != null && !clues.isEmpty()) {
            Map<String, ListField> aliasHits = new LinkedHashMap<>();
            for (String clue : clues) {
                for (ListField f : resolveClue(clue, dict)) {
                    aliasHits.putIfAbsent(f.uniqueKey(), f);
                }
            }
            trail.add("② 别名表：命中线索 " + clues.size() + " 条，定位字段 " + aliasHits.size() + " 个");
            if (aliasHits.size() == 1) {
                return AlignmentResult.alias(column, aliasHits.values().iterator().next(), trail);
            }
            if (aliasHits.size() > 1) {
                return AlignmentResult.ambiguous(column, new ArrayList<>(aliasHits.values()), trail,
                        "别名表把该列对上多个清单字段，一对多歧义，拒绝自动合并");
            }
            trail.add("② 别名表：线索未定位到任何清单字段，继续降级");
        } else {
            trail.add("② 别名表：未命中");
        }

        // ③ 交模型判定
        trail.add("③ 模型判定：词面不一致，移交归因模块");
        return AlignmentResult.model(column, trail);
    }

    /**
     * 批量对齐并按四类分组输出；同时检测「多个列对同一清单字段」的歧义并拒绝自动合并。
     */
    public Map<AlignmentResult.Group, List<AlignmentResult>> alignAll(List<String> columns,
                                                                       ListFieldDictionary dict) {
        List<AlignmentResult> raw = new ArrayList<>();
        if (columns != null) {
            for (String col : columns) {
                raw.add(align(col, dict));
            }
        }
        Map<String, Integer> targetCounts = new HashMap<>();
        for (AlignmentResult r : raw) {
            if (r.group() == AlignmentResult.Group.EXACT || r.group() == AlignmentResult.Group.ALIAS) {
                for (ListField f : r.matches()) {
                    targetCounts.merge(f.uniqueKey(), 1, Integer::sum);
                }
            }
        }
        List<AlignmentResult> adjusted = new ArrayList<>();
        for (AlignmentResult r : raw) {
            boolean conflict = (r.group() == AlignmentResult.Group.EXACT
                    || r.group() == AlignmentResult.Group.ALIAS)
                    && r.matches().stream().anyMatch(f -> targetCounts.getOrDefault(f.uniqueKey(), 0) > 1);
            if (conflict) {
                List<String> trail = new ArrayList<>(r.hitTrail());
                trail.add("批量对齐：多个来源列对上同一清单字段，拒绝自动合并");
                adjusted.add(AlignmentResult.ambiguous(r.sourceColumn(), r.matches(), trail,
                        "多个来源列对上同一清单字段，一对多歧义，进待人工裁决"));
            } else {
                adjusted.add(r);
            }
        }
        return AlignmentResult.groupBy(adjusted);
    }

    // ---------- 内部 ----------

    /** 把别名线索解析为具体清单字段候选。 */
    private List<ListField> resolveClue(String clue, ListFieldDictionary dict) {
        if (clue == null || clue.isBlank()) {
            return List.of();
        }
        int slash = clue.indexOf('/');
        if (slash > 0) {
            String section = clue.substring(0, slash).trim();
            String name = clue.substring(slash + 1).trim();
            return dict.find(section, name).map(List::of).orElse(List.of());
        }
        List<ListField> eq = new ArrayList<>();
        for (ListField f : dict.fields()) {
            if (f.fieldName().equals(clue)) {
                eq.add(f);
            }
        }
        if (!eq.isEmpty()) {
            return eq;
        }
        List<ListField> contains = new ArrayList<>();
        for (ListField f : dict.fields()) {
            if (f.fieldName().contains(clue)) {
                contains.add(f);
            }
        }
        if (!contains.isEmpty()) {
            return contains;
        }
        List<ListField> byNote = new ArrayList<>();
        for (ListField f : dict.fields()) {
            if (f.basisNote().contains(clue)) {
                byNote.add(f);
            }
        }
        return byNote;
    }

    /** 列名归一：小写，去掉下划线/空格/连字符等非字母数字（中文保留）。 */
    private static String norm(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : s.trim().toLowerCase(Locale.ROOT).toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || (c >= '\u4e00' && c <= '\u9fff')) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 内置别名映射（取自样例字典业务列名与项目内置映射）。 */
    private static Map<String, List<String>> builtinAliases() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("fee_amt", List.of("金额合计"));
        m.put("total_amount", List.of("金额合计"));
        m.put("patient_id", List.of("医保个人编号"));
        m.put("cust_id", List.of("医保个人编号"));
        m.put("bill_no", List.of("清单流水号"));
        m.put("bill_id", List.of("清单流水号"));
        m.put("name", List.of(ListField.SECTION_BASIC + "/姓名"));
        m.put("diag_code", List.of("出院主要诊断代码"));
        m.put("main_diag_name", List.of("出院主要诊断名称"));
        m.put("hosp_no", List.of("病案号"));
        m.put("settle_from", List.of("结算期间起始日期"));
        m.put("settle_to", List.of("结算期间截止日期"));
        m.put("fund_pay_amt", List.of("医保统筹基金支付金额"));
        return m;
    }
}
