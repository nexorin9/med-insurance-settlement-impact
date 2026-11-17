package com.chaosforge.medins.catalog;

import com.chaosforge.medins.model.ListField;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结算清单四分区结构校验与基础方案边界。
 *
 * <p>分区结构按《医疗保障基金结算清单填写规范》：基本信息 31 + 门诊慢特病诊疗信息 6 +
 * 住院诊疗信息 58 + 医疗收费信息 98 = 193 项（以目标统筹区执行的版本为准）。
 * 结构对不上就停下来报错，绝不按错的分区数继续算。
 *
 * <p>基础方案边界（Task 29）：结算清单主诊断与病案首页主诊断口径不同，须分别处理；
 * 本工具不得用「病案首页主诊断」口径校验「结算清单主诊断」。该边界以常量与判定方法固化，
 * 并把官方种子用例的口径依据接成字典侧固定基准。
 */
public final class SectionScheme {

    /** 四分区期望项数（不可变、按声明顺序），合计 193。 */
    public static final Map<String, Integer> EXPECTED;

    static {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put(ListField.SECTION_BASIC, 31);
        m.put(ListField.SECTION_OUTPATIENT_CHRONIC, 6);
        m.put(ListField.SECTION_INPATIENT, 58);
        m.put(ListField.SECTION_FEE, 98);
        EXPECTED = Collections.unmodifiableMap(m);
    }

    /** 期望总项数。 */
    public static final int EXPECTED_TOTAL = 193;

    // ---------- 基础方案边界：清单主诊断 ≠ 病案首页主诊断 ----------

    /** 结算清单主诊断所在分区与字段名（住院诊疗信息 / 出院主要诊断名称）。 */
    public static final String LIST_MAIN_DIAGNOSIS_SECTION = ListField.SECTION_INPATIENT;
    public static final String LIST_MAIN_DIAGNOSIS_FIELD = "出院主要诊断名称";

    /** 病案首页主诊断口径标识词 —— 禁止出现在结算清单主诊断的校验依据中。 */
    public static final String FRONT_PAGE_BASIS_TERM = "病案首页主诊断";

    /** 边界声明文本，供导出/说明/回归断言引用。 */
    public static final String BOUNDARY_NOTE =
            "结算清单主诊断与病案首页主诊断分别处理，两者口径不同；"
                    + "不得用病案首页主诊断口径校验结算清单主诊断";

    private SectionScheme() {
    }

    /** 结构校验：逐分区比较期望值与实际值，返回差异明细。 */
    public static Validation validate(ListFieldDictionary dict) {
        if (dict == null) {
            throw new IllegalArgumentException("待校验字典不可为 null");
        }
        Map<String, Integer> actual = dict.sectionCounts();
        List<String> differences = new ArrayList<>();

        for (Map.Entry<String, Integer> e : EXPECTED.entrySet()) {
            String section = e.getKey();
            int expected = e.getValue();
            int got = actual.getOrDefault(section, 0);
            if (expected != got) {
                differences.add("分区[" + section + "] 期望值=" + expected
                        + " 实际值=" + got + " 差异=" + (got - expected));
            }
        }
        for (String section : actual.keySet()) {
            if (!EXPECTED.containsKey(section)) {
                differences.add("出现四分区之外的 section[" + section + "] 期望值=0 实际值="
                        + actual.get(section) + " 差异=" + actual.get(section));
            }
        }
        if (EXPECTED_TOTAL != dict.size()) {
            differences.add("总项数 期望值=" + EXPECTED_TOTAL + " 实际值=" + dict.size()
                    + " 差异=" + (dict.size() - EXPECTED_TOTAL));
        }
        return new Validation(differences);
    }

    /** 文本是否使用了病案首页主诊断口径。 */
    public static boolean usesFrontPageBasis(String text) {
        return text != null && text.contains(FRONT_PAGE_BASIS_TERM);
    }

    /** 一段口径依据是否允许用于结算清单主诊断校验（不得串用首页口径）。 */
    public static boolean basisPermittedForListMainDiagnosis(String basisText) {
        return !usesFrontPageBasis(basisText);
    }

    /**
     * 官方种子用例的口径回归基准：逐条检查依据是否缺失、是否串用病案首页主诊断口径。
     * 返回空列表表示全部合规；返回的问题描述即为差异明细，供字典侧固定基准断言。
     */
    public static List<String> auditQcBases(List<String> caseBases) {
        List<String> problems = new ArrayList<>();
        if (caseBases == null) {
            problems.add("未提供任何口径依据");
            return problems;
        }
        for (int i = 0; i < caseBases.size(); i++) {
            String basis = caseBases.get(i);
            if (basis == null || basis.isBlank()) {
                problems.add("第" + (i + 1) + "条用例缺少 basis（口径依据）");
            } else if (!basisPermittedForListMainDiagnosis(basis)) {
                problems.add("第" + (i + 1) + "条用例串用「" + FRONT_PAGE_BASIS_TERM
                        + "」口径校验结算清单主诊断，违反基础方案边界");
            }
        }
        return Collections.unmodifiableList(problems);
    }

    /** 结构校验结果：是否通过 + 差异明细（期望值/实际值/差异条目）。 */
    public static final class Validation {
        private final List<String> differences;

        private Validation(List<String> differences) {
            this.differences = List.copyOf(differences);
        }

        public boolean ok() {
            return differences.isEmpty();
        }

        public List<String> differences() {
            return differences;
        }
    }
}
