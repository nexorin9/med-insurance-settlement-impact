package com.chaosforge.medins.report;

import com.chaosforge.medins.model.ImpactRecord;
import com.chaosforge.medins.model.ImpactScope;
import com.chaosforge.medins.model.Verdict;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 两版取数视图影响面差异回放。
 *
 * <p>输入是两版视图各自算出的影响面记录（可来自历史留存的 SQL 文件，也可来自院内库读取结果，
 * 两种来源共用本计算路径），以「分区 + 字段名」为主键比对，输出五类差异：
 * 新增 / 消失 / 判定变化 / 口径变化 / 来源变化。
 *
 * <p>主键含分区，故同名不同分区的字段不会被误判为同一字段的变化；
 * 来源列改名归入「来源变化」，不制造新增/消失的假差异。
 */
public final class TwoVersionDiff {

    private TwoVersionDiff() {
    }

    public static DiffResult compare(List<ImpactRecord> older, List<ImpactRecord> newer) {
        Map<String, ImpactRecord> oldIndex = index(older);
        Map<String, ImpactRecord> newIndex = index(newer);

        List<DiffResult.Entry> added = new ArrayList<>();
        List<DiffResult.Entry> removed = new ArrayList<>();
        List<DiffResult.Entry> verdictChanged = new ArrayList<>();
        List<DiffResult.Entry> scopeChanged = new ArrayList<>();
        List<DiffResult.Entry> sourceChanged = new ArrayList<>();

        for (Map.Entry<String, ImpactRecord> entry : newIndex.entrySet()) {
            String key = entry.getKey();
            ImpactRecord n = entry.getValue();
            ImpactRecord o = oldIndex.get(key);
            if (o == null) {
                added.add(DiffResult.Entry.of(
                        DiffResult.Kind.ADDED, null, n, "（无）", describe(n)));
                continue;
            }
            if (!Objects.equals(o.verdict(), n.verdict())) {
                verdictChanged.add(DiffResult.Entry.of(
                        DiffResult.Kind.VERDICT_CHANGED, o, n, verdictLabel(o.verdict()), verdictLabel(n.verdict())));
            }
            if (!Objects.equals(o.scope(), n.scope())) {
                scopeChanged.add(DiffResult.Entry.of(
                        DiffResult.Kind.SCOPE_CHANGED, o, n, scopeLabel(o.scope()), scopeLabel(n.scope())));
            }
            if (!Objects.equals(o.sourceColumn(), n.sourceColumn())) {
                sourceChanged.add(DiffResult.Entry.of(
                        DiffResult.Kind.SOURCE_CHANGED, o, n, o.sourceColumn(), n.sourceColumn()));
            }
        }

        for (Map.Entry<String, ImpactRecord> entry : oldIndex.entrySet()) {
            if (!newIndex.containsKey(entry.getKey())) {
                removed.add(DiffResult.Entry.of(
                        DiffResult.Kind.REMOVED, entry.getValue(), null, describe(entry.getValue()), "（无）"));
            }
        }

        return new DiffResult(added, removed, verdictChanged, scopeChanged, sourceChanged);
    }

    /** 以「分区 + 字段名」建索引；同一主键出现多条时保留首条，保证结果可复现。 */
    private static Map<String, ImpactRecord> index(List<ImpactRecord> records) {
        Map<String, ImpactRecord> map = new LinkedHashMap<>();
        if (records != null) {
            for (ImpactRecord record : records) {
                map.putIfAbsent(ReportText.fieldKey(record), record);
            }
        }
        return map;
    }

    private static String describe(ImpactRecord r) {
        return "判定=" + verdictLabel(r.verdict())
                + "/口径=" + scopeLabel(r.scope())
                + "/来源=" + r.sourceColumn();
    }

    private static String verdictLabel(Verdict verdict) {
        return verdict == null ? "未归因" : verdict.labelZh();
    }

    private static String scopeLabel(ImpactScope scope) {
        return scope == null ? "未标注口径" : scope.labelZh();
    }
}
