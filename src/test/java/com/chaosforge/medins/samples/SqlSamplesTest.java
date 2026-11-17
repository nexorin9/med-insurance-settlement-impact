package com.chaosforge.medins.samples;

import com.chaosforge.medins.model.ListField;
import com.chaosforge.medins.sqlparse.SampleViews;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 样例数据验收（Task 2 / SqlSamplesTest，纯 headless、不访问网络）：
 * 12 段视图 SQL、193 行字段字典（31/6/58/98）、8 条官方种子用例、
 * 8 张 TPC-H 种子表、两个可执行且纯 ASCII 的脚本。
 */
class SqlSamplesTest {

    private static final String[] TABLES = {
            "customer", "lineitem", "nation", "orders",
            "part", "partsupp", "region", "supplier"
    };

    private static final String DICT_HEADER =
            "section,field_name,field_code,value_type,required_flag,basis_note";

    // ---------- helpers ----------

    /** 优先从本测试类所在位置（target/test-classes）上溯定位项目根，其次从工作目录上溯。 */
    private static Path projectRoot() throws IOException {
        try {
            URL loc = SqlSamplesTest.class.getProtectionDomain().getCodeSource().getLocation();
            Path d = Paths.get(loc.toURI());
            for (int i = 0; i < 6 && d != null; i++) {
                if (Files.isRegularFile(d.resolve("pom.xml"))
                        && Files.isDirectory(d.resolve("scripts"))) {
                    return d;
                }
                d = d.getParent();
            }
        } catch (URISyntaxException | RuntimeException ignore) {
            // 回退到工作目录上溯
        }
        Path d = Paths.get("").toAbsolutePath();
        while (d != null) {
            if (Files.isRegularFile(d.resolve("pom.xml"))
                    && Files.isDirectory(d.resolve("scripts"))) {
                return d;
            }
            d = d.getParent();
        }
        throw new IOException("无法定位项目根（pom.xml + scripts/），cwd="
                + Paths.get("").toAbsolutePath());
    }

    private static InputStream open(String path) {
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        InputStream in = ctx == null ? null : ctx.getResourceAsStream(path);
        if (in == null) {
            in = SqlSamplesTest.class.getClassLoader().getResourceAsStream(path);
        }
        return in;
    }

    private static String readResource(String path) throws IOException {
        try (InputStream in = open(path)) {
            if (in == null) {
                throw new IOException("classpath 资源缺失：" + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> resourceLines(String path) throws IOException {
        String text = readResource(path);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        String[] arr = text.split("\\R", -1);
        List<String> out = new ArrayList<>();
        for (String s : arr) {
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /** 轻量 RFC4180 单行 CSV 解析：支持双引号包裹的字段、字段内逗号与 "" 转义。 */
    private static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cur.append(c);
                }
            } else {
                if (c == '"') {
                    quoted = true;
                } else if (c == ',') {
                    out.add(cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
            }
        }
        out.add(cur.toString());
        return out;
    }

    // ---------- tests ----------

    @Test
    void twelveSqlViewsExistAndAreNonEmpty() throws IOException {
        assertEquals(12, SampleViews.VIEW_NAMES.size(), "VIEW_NAMES 必须恰为 12 段");
        assertEquals(12, SampleViews.count(), "SampleViews.count() 必须为 12");
        Map<String, String> all = SampleViews.loadAll();
        assertEquals(12, all.size(), "loadAll() 必须返回 12 段 SQL");
        for (String name : SampleViews.VIEW_NAMES) {
            assertTrue(all.containsKey(name), "缺失视图：" + name);
            String sql = all.get(name);
            assertNotNull(sql, "SQL 为 null：" + name);
            assertFalse(sql.isBlank(), "SQL 为空：" + name);
            assertTrue(sql.toUpperCase(java.util.Locale.ROOT).contains("SELECT"),
                    "视图缺少 SELECT：" + name);
        }
    }

    @Test
    void dictionaryHas193RowsAndExactSectionCounts() throws IOException {
        List<String> lines = resourceLines("samples/settlement_fields.csv");
        assertFalse(lines.isEmpty(), "字段字典 CSV 为空");
        assertEquals(DICT_HEADER, lines.get(0), "CSV 表头不符");

        List<List<String>> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            rows.add(parseCsvLine(lines.get(i)));
        }
        assertEquals(193, rows.size(), "settlement_fields.csv 必须恰为 193 行数据");

        Set<String> allowedTypes = Set.of(
                ListField.VALUE_TYPE_CHAR,
                ListField.VALUE_TYPE_NUMBER,
                ListField.VALUE_TYPE_DATE,
                ListField.VALUE_TYPE_CODE);
        Map<String, Integer> counts = new LinkedHashMap<>();
        Set<String> keys = new HashSet<>();

        for (List<String> row : rows) {
            assertEquals(6, row.size(), "每行必须 6 列：" + row);
            String section = row.get(0);
            String fieldName = row.get(1);
            String valueType = row.get(3);
            String required = row.get(4);
            String note = row.get(5);
            assertTrue(allowedTypes.contains(valueType),
                    "非法 value_type：" + valueType + " @ " + section + "/" + fieldName);
            assertTrue("true".equals(required) || "false".equals(required),
                    "非法 required_flag：" + required);
            assertFalse(note.isBlank(), "basis_note 不得为空：" + section + "/" + fieldName);
            assertTrue(keys.add(section + "\u0001" + fieldName),
                    "(section, field_name) 重复：" + section + "/" + fieldName);
            counts.merge(section, 1, Integer::sum);
        }

        assertEquals(31, counts.getOrDefault(ListField.SECTION_BASIC, 0).intValue(), "基本信息项数");
        assertEquals(6, counts.getOrDefault(ListField.SECTION_OUTPATIENT_CHRONIC, 0).intValue(), "门诊慢特病诊疗信息项数");
        assertEquals(58, counts.getOrDefault(ListField.SECTION_INPATIENT, 0).intValue(), "住院诊疗信息项数");
        assertEquals(98, counts.getOrDefault(ListField.SECTION_FEE, 0).intValue(), "医疗收费信息项数");
        assertEquals(4, counts.size(), "必须恰为四个分区");
    }

    @Test
    void eightQcCasesParseAndHaveUniqueIds() throws IOException {
        String json = readResource("cases/settlement_qc_cases.json");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(json);
        assertNotNull(root, "用例 JSON 必须可解析");
        assertTrue(root.isObject(), "用例 JSON 根节点必须是对象");
        JsonNode cases = root.get("cases");
        assertNotNull(cases, "缺少 cases 数组");
        assertTrue(cases.isArray(), "cases 必须是数组");
        assertEquals(8, cases.size(), "必须恰为 8 条官方种子用例");

        Set<String> ids = new HashSet<>();
        for (JsonNode c : cases) {
            assertTrue(c.isObject(), "每条用例必须是对象");
            String id = c.path("case_id").asText("");
            assertFalse(id.isBlank(), "case_id 缺失或为空");
            assertTrue(ids.add(id), "case_id 重复：" + id);
            assertTrue(c.has("input") && c.has("expect")
                            && c.has("basis") && c.has("confidence"),
                    "用例缺少必要键：" + id);
        }
    }

    @Test
    void seedDirectoryHasEightTables() throws IOException {
        Path seed = projectRoot().resolve("src/test/resources/seed");
        assertTrue(Files.isDirectory(seed), "seed 目录缺失：" + seed);
        for (String t : TABLES) {
            Path f = seed.resolve(t + ".tbl");
            assertTrue(Files.isRegularFile(f), "seed 表缺失：" + t);
            assertTrue(Files.size(f) > 0, "seed 表为空：" + t);
        }
        assertEquals(8, TABLES.length, "TPC-H 底层表数量");
    }

    @Test
    void scriptsExistAndAreExecutableAndAscii() throws IOException {
        Path scripts = projectRoot().resolve("scripts");
        assertTrue(Files.isDirectory(scripts), "scripts 目录缺失");
        for (String name : new String[]{"fetch-tpch.sh", "gen-samples.sh"}) {
            Path f = scripts.resolve(name);
            assertTrue(Files.isRegularFile(f), "脚本缺失：" + name);
            assertTrue(Files.isExecutable(f), "脚本不可执行：" + name);
            assertTrue(Files.size(f) > 0, "脚本为空：" + name);
            byte[] bytes = Files.readAllBytes(f);
            for (byte b : bytes) {
                int u = b & 0xFF;
                assertTrue(u == 9 || u == 10 || u == 13 || (u >= 32 && u <= 126),
                        "脚本必须纯 ASCII：" + name + " 非法字节 " + u);
            }
        }
    }

    @Test
    void generatedTpchTablesArePresentWhenDataDirExists() throws IOException {
        // data/ 在 .gitignore 中；目录存在时校验 8 张表，否则以已提交的 seed 集为准。
        Path tpch = projectRoot().resolve("data/tpch");
        if (!Files.isDirectory(tpch)) {
            Path seed = projectRoot().resolve("src/test/resources/seed");
            for (String t : TABLES) {
                assertTrue(Files.isRegularFile(seed.resolve(t + ".tbl")),
                        "data/tpch 缺失时以 seed 为准，表缺失：" + t);
            }
            return;
        }
        for (String t : TABLES) {
            Path f = tpch.resolve(t + ".tbl");
            assertTrue(Files.isRegularFile(f), "data/tpch 表缺失：" + t);
            assertTrue(Files.size(f) > 0, "data/tpch 表为空：" + t);
        }
    }
}
