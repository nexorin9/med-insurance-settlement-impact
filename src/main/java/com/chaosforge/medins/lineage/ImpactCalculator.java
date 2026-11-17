package com.chaosforge.medins.lineage;

import com.chaosforge.medins.model.ChangeKind;
import com.chaosforge.medins.model.FieldKey;
import com.chaosforge.medins.model.ImpactRecord;
import com.chaosforge.medins.model.ImpactScope;
import com.chaosforge.medins.model.ImpactStatus;
import com.chaosforge.medins.model.ImpactType;
import com.chaosforge.medins.model.ListField;
import com.chaosforge.medins.model.Verdict;
import com.chaosforge.medins.sqlparse.EdgeConfidencePolicy;
import com.chaosforge.medins.sqlparse.SqlDigest;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 影响面记录构造：把「图上的下游可达子集」与「结算清单字段字典」合成可落库、可导出的记录。
 *
 * <p>一条可达字段 → 一条影响记录。置信度复用 {@link EdgeConfidencePolicy}，按跳数与最弱边类型衰减；
 * 低置信度、跳数 ≥ 3（需人工确认）或经由 FILTER 边的记录进入「疑似波及」，其余为「实质受影响」。
 * 必填字段优先，同必填按分区顺序（基本信息 → 门诊慢特病 → 住院诊疗 → 医疗收费）；
 * basis_note 随行写入 reasonZh。批次一律用 {@code <EXAMPLE_BATCH>} 占位。
 * 幂等键按契约用 {@link SqlDigest#idempotencyKey}（改动事件级，同一改动的多条记录共享）。
 */
public final class ImpactCalculator {

    /** 样例数据批次占位，禁止出现形似真实批次号的字面量。 */
    public static final String BATCH_PLACEHOLDER = "<EXAMPLE_BATCH>";

    private ImpactCalculator() { }

    public static List<ImpactRecord> calculate(FieldKey seed, ReachabilityResult reach,
                                               List<ListField> dictionaryFields,
                                               String sqlDigest, ChangeKind changeKind) {
        Objects.requireNonNull(seed, "seed");
        Objects.requireNonNull(reach, "reach");
        List<ListField> dict = dictionaryFields == null ? List.of() : dictionaryFields;
        ChangeKind kind = changeKind == null ? ChangeKind.LOGIC_CHANGED : changeKind;
        String viewName = seed.view();
        String changedField = seed.column();
        String idem = SqlDigest.idempotencyKey(viewName, sqlDigest, changedField);
        ImpactScope scope = reach.scope();

        List<Entry> entries = new ArrayList<>();
        for (FieldKey f : reach.fields()) {
            int hops = reach.hops().getOrDefault(f, 0);
            List<PathStep> path = reach.paths().getOrDefault(f, List.of());
            double confidence = LineageUtil.confidenceOfPath(path, hops);
            boolean lowConf = confidence < EdgeConfidencePolicy.SUSPECTED_THRESHOLD;
            boolean filterOnPath = LineageUtil.hasFilter(path);
            boolean needsHuman = reach.needsHumanConfirmation(f);

            // 低置信度 / 需人工确认 / 过滤边口径变化 → 疑似波及，不自动判定实质影响
            ImpactType impact = (lowConf || needsHuman || filterOnPath)
                    ? ImpactType.SUSPECTED : ImpactType.DIRECT;
            Verdict verdict = impact == ImpactType.DIRECT
                    ? Verdict.MATERIALLY_AFFECTED : Verdict.SUSPECTED;

            ListField match = findMatch(dict, f);
            String section = match != null ? match.section() : f.view();
            String listFieldName = match != null ? match.fieldName() : f.column();
            boolean required = match != null && match.requiredFlag();
            String basisNote = match != null ? match.basisNote() : "";
            int rank = LineageUtil.sectionRank(section);

            String reason = reasonZh(scope, viewName, changedField, kind, f, hops,
                    impact, verdict, confidence, path, basisNote);

            ImpactRecord rec = ImpactRecord.builder()
                    .idempotencyKey(idem)
                    .viewName(viewName)
                    .viewDialect("ORACLE")
                    .sqlDigest(sqlDigest == null ? "" : sqlDigest)
                    .changedField(changedField)
                    .changeKind(kind)
                    .section(section)
                    .listFieldName(listFieldName)
                    .sourceColumn(f.column())
                    .impact(impact)
                    .confidence(confidence)
                    .scope(scope)
                    .hops(hops)
                    .batch(BATCH_PLACEHOLDER)
                    .verdict(verdict)
                    .reasonZh(reason)
                    .status(ImpactStatus.COMPUTED)
                    .build();
            entries.add(new Entry(rec, required, rank, listFieldName));
        }

        // 稳定排序：必填优先 → 分区顺序 → 字段名；List.sort 稳定，同分区内保持 BFS 顺序
        entries.sort((a, b) -> {
            int c = Boolean.compare(b.required(), a.required()); // true 排前
            if (c != 0) {
                return c;
            }
            c = Integer.compare(a.rank(), b.rank());
            if (c != 0) {
                return c;
            }
            return a.name().compareTo(b.name());
        });

        List<ImpactRecord> out = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            out.add(e.rec());
        }
        return out;
    }

    /** 确定性对齐：字典项的字段名/字段代码与可达字段的列名基名/全名一致即命中，取首个匹配。 */
    private static ListField findMatch(List<ListField> dict, FieldKey f) {
        Set<String> fieldKeys = new LinkedHashSet<>();
        fieldKeys.add(upper(f.column()));
        fieldKeys.add(LineageUtil.baseColumn(f.column()));
        fieldKeys.add(upper(f.key()));
        for (ListField lf : dict) {
            String name = upper(lf.fieldName());
            String code = upper(lf.fieldCode());
            if ((!name.isEmpty() && fieldKeys.contains(name))
                    || (!code.isEmpty() && fieldKeys.contains(code))) {
                return lf;
            }
        }
        return null;
    }

    private static String reasonZh(ImpactScope scope, String viewName, String changedField,
                                   ChangeKind kind, FieldKey f, int hops,
                                   ImpactType impact, Verdict verdict, double confidence,
                                   List<PathStep> path, String basisNote) {
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(scope.labelZh()).append("/").append(scope.wireName()).append("】")
                .append("改动 ").append(viewName).append(".").append(changedField)
                .append("（").append(kind.labelZh()).append("）沿 ").append(hops)
                .append(" 跳波及 ").append(f.key())
                .append("；影响类型 ").append(impact.labelZh())
                .append("；置信度 ").append(String.format(Locale.ROOT, "%.2f", confidence))
                .append("（按跳数与最弱边 ").append(LineageUtil.weakestKind(path).labelZh()).append(" 衰减）")
                .append("；判定 ").append(verdict.labelZh());
        if (basisNote != null && !basisNote.isBlank()) {
            sb.append("；口径注释：").append(basisNote);
        }
        return sb.toString();
    }

    private static String upper(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    /** 排序辅助：携带字典派生出的必填标记、分区序号、字段名。 */
    private record Entry(ImpactRecord rec, boolean required, int rank, String name) { }
}
