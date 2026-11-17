package com.chaosforge.medins.report;

import com.chaosforge.medins.model.ImpactRecord;
import com.chaosforge.medins.model.ImpactScope;

import java.util.List;
import java.util.Map;

/**
 * 生成可直接粘给厂商工程师的中文影响说明（Markdown 草稿）。
 *
 * <p>结构：改动点 + 受影响清单字段表 + 批次 + 逐条理由。
 * 逐条理由只转述 {@link NoteContext#evidence()} 里的证据链中文短句与
 * {@link ImpactRecord#reasonZh()} 的归因理由，不重新编造措辞，保证与界面所见一致。
 *
 * <p>安全边界：标为「草稿（未人工确认）」；批次一律 {@code <EXAMPLE_BATCH>} 占位；
 * 只含字段级关系，不含患者明细、真实批次号与凭据。
 */
public final class VendorNoteWriter {

    /** 置信度阈值，低于此值标注需人工确认（与 sqlparse 的 EdgeConfidencePolicy 阈值对齐）。 */
    private static final double SUSPECTED_THRESHOLD = 0.65d;

    private VendorNoteWriter() {
    }

    public static String write(List<ImpactRecord> records, NoteContext ctx) {
        NoteContext context = ctx == null ? NoteContext.builder().build() : ctx;
        List<ImpactRecord> rows = records == null ? List.of() : records;
        StringBuilder sb = new StringBuilder();

        sb.append("# 取数视图 ").append(nz(context.viewName()))
                .append(" 改动影响说明 · 草稿（未人工确认）\n\n");
        sb.append("> 本文为草稿（未人工确认），需人工逐行确认后方可回写台账。\n\n");

        appendChangePoint(sb, context);
        appendAffectedFields(sb, rows, context);
        appendBatch(sb);
        appendReasons(sb, rows, context);

        sb.append("\n> 本说明为草稿（未人工确认），仅含字段级关系，不含患者明细、真实批次号与凭据；"
                + "需人工逐行确认后方可回写台账。\n");
        return sb.toString();
    }

    private static void appendChangePoint(StringBuilder sb, NoteContext ctx) {
        sb.append("## 改动点\n");
        sb.append("- 视图：").append(nz(ctx.viewName())).append("\n");
        sb.append("- 改动列：").append(nz(ctx.changedField())).append("\n");
        sb.append("- 改动类型：").append(ctx.changeKind() == null ? "未登记" : ctx.changeKind().labelZh()).append("\n");
        sb.append("- 改动说明：").append(nz(ctx.changeNoteZh())).append("\n");
        sb.append("- 视图摘要：").append(ReportText.shortDigest(ctx.sqlDigest())).append("\n");
        sb.append("- 计算口径：").append(scopeLabel(ctx.scope())).append("\n");
        sb.append("- 归因状态：").append(ctx.degraded() ? "归因未生成（模型不可达，已降级，禁止回写）" : "归因已生成").append("\n");
        sb.append("- 置信度：见下表逐条标注，低于阈值的已标注「疑似波及，需人工确认」\n");
        sb.append("- 范围提示：本说明仅列出本次口径下命中的字段；保守口径可能比严格口径多出疑似波及项，"
                + "均需人工确认，不等同业务全部影响\n\n");
    }

    private static void appendAffectedFields(StringBuilder sb, List<ImpactRecord> rows, NoteContext ctx) {
        sb.append("## 受影响结算清单字段（").append(scopeLabel(ctx.scope())).append("）\n");
        sb.append("| 分区 | 字段 | 来源列 | 判定 | 置信度 | 口径 | 跳数 |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        for (ImpactRecord r : rows) {
            sb.append("| ").append(escapeCell(r.section()))
                    .append(" | ").append(escapeCell(r.listFieldName()))
                    .append(" | ").append(escapeCell(r.sourceColumn()))
                    .append(" | ").append(escapeCell(verdictLabel(r)))
                    .append(" | ").append(ReportText.fmtConfidence(r.confidence()))
                    .append(" | ").append(escapeCell(scopeLabel(r.scope())))
                    .append(" | ").append(r.hops())
                    .append(" |\n");
        }
        sb.append("\n");
    }

    private static void appendBatch(StringBuilder sb) {
        sb.append("## 批次\n");
        sb.append("- ").append(ReportText.EXAMPLE_BATCH).append("\n\n");
    }

    private static void appendReasons(StringBuilder sb, List<ImpactRecord> rows, NoteContext ctx) {
        sb.append("## 逐条理由\n\n");
        if (rows.isEmpty()) {
            sb.append("- 本次没有命中受影响字段。\n");
            return;
        }
        for (ImpactRecord r : rows) {
            sb.append("### ").append(r.section()).append(" / ").append(r.listFieldName()).append("\n");
            sb.append("- 判定：").append(verdictLabel(r))
                    .append("；置信度：").append(ReportText.fmtConfidence(r.confidence()))
                    .append("；口径：").append(scopeLabel(r.scope()))
                    .append("；跳数：").append(r.hops()).append("\n");
            sb.append("- 证据链：").append(evidenceFor(ctx, r)).append("\n");
            sb.append("- 归因理由：").append(nz(r.reasonZh())).append("\n");
            if (r.confidence() < SUSPECTED_THRESHOLD) {
                sb.append("- 提示：疑似波及，需人工确认（置信度低于阈值）\n");
            }
            if (r.verdict() != null && r.verdict().needsHumanArbitration()) {
                sb.append("- 提示：需人工裁决（").append(r.verdict().labelZh()).append("）\n");
            }
            sb.append("\n");
        }
    }

    /** 证据链短句按「字段名 → 唯一键 → 分区/字段名」顺序查找，原样转述，不加工措辞。 */
    private static String evidenceFor(NoteContext ctx, ImpactRecord r) {
        Map<String, String> evidence = ctx.evidence();
        String value = evidence.get(r.listFieldName());
        if (value == null) {
            value = evidence.get(ReportText.fieldKey(r));
        }
        if (value == null) {
            value = evidence.get(r.section() + "/" + r.listFieldName());
        }
        return value == null || value.isBlank() ? "（未提供证据链短句）" : value;
    }

    private static String verdictLabel(ImpactRecord r) {
        return r.verdict() == null ? "未归因" : r.verdict().labelZh();
    }

    private static String scopeLabel(ImpactScope scope) {
        return scope == null ? "未标注口径" : scope.labelZh();
    }

    /** Markdown 表格单元格里出现竖线时转义，避免破坏表格结构。 */
    private static String escapeCell(String raw) {
        String value = nz(raw);
        return value.replace("|", "\\|").replace("\n", " ").replace("\r", " ");
    }

    private static String nz(String value) {
        return value == null || value.isBlank() ? "（未登记）" : value;
    }
}
