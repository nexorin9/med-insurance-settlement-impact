package com.chaosforge.medins.catalog;

import com.chaosforge.medins.model.ListField;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 29 / CatalogSemanticsTest：必填优先、basis_note 随行、同名不同分区不合并、
 * 基础方案边界（清单主诊断 ≠ 病案首页主诊断）与 8 条官方种子用例口径回归。
 */
class CatalogSemanticsTest {

    private static ListFieldDictionary loadReal() throws Exception {
        InputStream in = CatalogSemanticsTest.class.getClassLoader()
                .getResourceAsStream("samples/settlement_fields.csv");
        assertNotNull(in);
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return ListFieldDictionary.fromCsv(reader);
        }
    }

    private static String readResource(String path) throws Exception {
        InputStream in = CatalogSemanticsTest.class.getClassLoader().getResourceAsStream(path);
        assertNotNull(in, "classpath 缺少 " + path);
        try (InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        }
    }

    private static List<String> qcBases() throws Exception {
        JsonNode root = new ObjectMapper().readTree(readResource("cases/settlement_qc_cases.json"));
        List<String> bases = new ArrayList<>();
        for (JsonNode c : root.path("cases")) {
            bases.add(c.path("basis").asText(""));
        }
        return bases;
    }

    @Test
    void requiredFirstPutsRequiredBeforeOptionalStably() throws Exception {
        List<ListField> sorted = loadReal().requiredFirst();
        boolean optionalSeen = false;
        int prevRank = -1;
        for (ListField f : sorted) {
            if (f.requiredFlag()) {
                assertFalse(optionalSeen, "必填字段必须排在可选字段之前");
                int rank = ListFieldDictionary.SECTION_ORDER.indexOf(f.section());
                assertTrue(rank >= prevRank, "同为必填按分区顺序稳定排序");
                prevRank = rank;
                assertFalse(f.basisNote().isBlank(), "排序后 basis_note 仍随行");
            } else {
                optionalSeen = true;
            }
        }
        assertTrue(optionalSeen);
    }

    @Test
    void basisNoteTravelsWithEveryRow() throws Exception {
        ListFieldDictionary dict = loadReal();
        for (ListField f : dict.fields()) {
            assertFalse(f.basisNote().isBlank(), "每个字段须随行带出 basis_note 口径注释");
        }
        ListField fee = dict.find(ListField.SECTION_FEE, "医疗总费用金额合计").orElseThrow();
        assertTrue(fee.basisNote().contains("规范") || fee.basisNote().contains("口径"));

        ListField inSorted = dict.requiredFirst().stream()
                .filter(f -> f.fieldName().equals("医疗总费用金额合计"))
                .findFirst().orElseThrow();
        assertEquals(fee.basisNote(), inSorted.basisNote(), "必填优先视图里 basis_note 不丢失");
    }

    @Test
    void sameNameDifferentSectionNotMerged() {
        ListFieldDictionary dict = ListFieldDictionary.fromRows(List.of(
                new ListField(ListField.SECTION_INPATIENT, "出院主要诊断名称", "I16", "字符", true, "清单主诊断口径"),
                new ListField(ListField.SECTION_BASIC, "出院主要诊断名称", "X", "字符", false, "同名不同分区口径")));

        assertEquals(2, dict.size(), "同名不同分区按 (section+field_name) 分别保留");
        ListField a = dict.find(ListField.SECTION_INPATIENT, "出院主要诊断名称").orElseThrow();
        ListField b = dict.find(ListField.SECTION_BASIC, "出院主要诊断名称").orElseThrow();
        assertNotEquals(a, b);
        assertNotEquals(a.basisNote(), b.basisNote(), "口径注释不得串用/误合并");
    }

    @Test
    void eightOfficialQcCasesLoadWithCaseIdExpectAndBasis() throws Exception {
        JsonNode root = new ObjectMapper().readTree(readResource("cases/settlement_qc_cases.json"));
        JsonNode cases = root.path("cases");
        assertEquals(8, cases.size(), "8 条官方种子用例必须都能被加载");

        for (JsonNode c : cases) {
            assertTrue(c.has("case_id") && !c.path("case_id").asText("").isBlank(),
                    "每条用例须含 case_id");
            assertTrue(c.has("expect") && c.get("expect").isObject(), "每条用例须含 expect");
            assertTrue(c.has("basis") && !c.path("basis").asText("").isBlank(),
                    "每条用例须含 basis（口径依据）");
        }
    }

    @Test
    void frontPageMainDiagnosisIsNeverUsedForListValidation() throws Exception {
        ListFieldDictionary dict = loadReal();

        // 边界声明：清单主诊断与病案首页主诊断分别处理。
        assertTrue(SectionScheme.BOUNDARY_NOTE.contains(SectionScheme.FRONT_PAGE_BASIS_TERM));
        assertTrue(SectionScheme.BOUNDARY_NOTE.contains("不得"));
        assertNotEquals(SectionScheme.FRONT_PAGE_BASIS_TERM, SectionScheme.LIST_MAIN_DIAGNOSIS_FIELD);

        // 清单主诊断字段的口径依据独立，不含首页主诊断口径。
        ListField mainDiag = dict.find(SectionScheme.LIST_MAIN_DIAGNOSIS_SECTION,
                SectionScheme.LIST_MAIN_DIAGNOSIS_FIELD).orElseThrow();
        assertFalse(mainDiag.basisNote().contains(SectionScheme.FRONT_PAGE_BASIS_TERM));
        assertTrue(SectionScheme.basisPermittedForListMainDiagnosis(mainDiag.basisNote()));

        // 官方 8 条种子用例的口径依据全部合规，未串用首页口径。
        List<String> bases = qcBases();
        assertEquals(8, bases.size());
        assertTrue(SectionScheme.auditQcBases(bases).isEmpty(),
                "官方种子用例不得用病案首页主诊断口径校验结算清单主诊断");

        // 显式回归断言：一旦串用首页口径，必须被判违规。
        String wrong = "依据" + SectionScheme.FRONT_PAGE_BASIS_TERM + "口径校验结算清单主诊断";
        assertTrue(SectionScheme.usesFrontPageBasis(wrong));
        assertFalse(SectionScheme.basisPermittedForListMainDiagnosis(wrong));
        assertFalse(SectionScheme.auditQcBases(List.of(wrong)).isEmpty(),
                "首页主诊断口径用于清单校验必须被显式拒绝");
    }
}
