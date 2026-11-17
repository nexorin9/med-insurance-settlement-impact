package com.chaosforge.medins.catalog;

import com.chaosforge.medins.model.ListField;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 结算清单字段字典（只读值对象）。
 *
 * <p>权威来源是院内 HIS 结算模块字段元数据表；本类同时提供 {@code fromCsv(Path/Reader)} 与
 * {@code fromRows(List<ListField>)} 两条入口 —— 后者即「从 HIS 字段字典 JDBC 读取」的同构入口，
 * 两种来源构造出的 {@link ListField} 走同一套索引、校验与排序逻辑，产出同一模型。
 *
 * <p>加载期强校验，结构对不上立即停下来报错，绝不按错的分区数继续算：
 * <ul>
 *   <li>CSV 支持 UTF-8 BOM 与 RFC4180 引号转义（字段内逗号、{@code ""} 转义、引号内换行）；</li>
 *   <li>section 必须落在四分区枚举内，value_type 取值 字符/数值/日期/代码，required_flag 为布尔；</li>
 *   <li>按 (section + field_name) 唯一键索引，重复键报错并列出冲突的两行，不静默取第一条。</li>
 * </ul>
 */
public final class ListFieldDictionary {

    private static final String HEADER_SECTION = "section";
    private static final String HEADER_FIELD_NAME = "field_name";

    /** 四分区声明顺序，同时用于稳定排序与分区计数。 */
    public static final List<String> SECTION_ORDER = List.of(
            ListField.SECTION_BASIC,
            ListField.SECTION_OUTPATIENT_CHRONIC,
            ListField.SECTION_INPATIENT,
            ListField.SECTION_FEE);

    private static final Set<String> SECTIONS = new LinkedHashSet<>(SECTION_ORDER);

    private static final Set<String> VALUE_TYPES = Set.of(
            ListField.VALUE_TYPE_CHAR,
            ListField.VALUE_TYPE_NUMBER,
            ListField.VALUE_TYPE_DATE,
            ListField.VALUE_TYPE_CODE);

    private final List<ListField> fields;
    private final Map<String, ListField> index;
    private final Map<String, List<ListField>> bySection;
    private final Map<String, Integer> sectionCounts;

    private ListFieldDictionary(List<ListField> fields,
                                Map<String, ListField> index,
                                Map<String, List<ListField>> bySection,
                                Map<String, Integer> sectionCounts) {
        this.fields = List.copyOf(fields);
        this.index = Collections.unmodifiableMap(index);
        Map<String, List<ListField>> frozenBySection = new LinkedHashMap<>();
        for (Map.Entry<String, List<ListField>> e : bySection.entrySet()) {
            frozenBySection.put(e.getKey(), List.copyOf(e.getValue()));
        }
        this.bySection = Collections.unmodifiableMap(frozenBySection);
        this.sectionCounts = Collections.unmodifiableMap(new LinkedHashMap<>(sectionCounts));
    }

    // ---------- 三条构造入口 ----------

    /** 从 CSV 文件路径加载（UTF-8）。 */
    public static ListFieldDictionary fromCsv(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return fromCsv(reader);
        }
    }

    /** 从字符流加载 CSV；支持 BOM 与 RFC4180 转义。 */
    public static ListFieldDictionary fromCsv(Reader reader) throws IOException {
        if (reader == null) {
            throw new IllegalArgumentException("字段字典 Reader 不可为 null");
        }
        return parse(readAll(reader));
    }

    /**
     * 从已构造的 {@link ListField} 列表加载 —— 供 HIS 字段字典 JDBC 读取使用，
     * 与 CSV 入口共用同一索引/校验/排序逻辑（同构入口）。
     */
    public static ListFieldDictionary fromRows(List<ListField> rows) {
        List<ListField> copy = rows == null ? List.of() : List.copyOf(rows);
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < copy.size(); i++) {
            ListField f = copy.get(i);
            labels.add("第" + (i + 1) + "条[" + f.section() + "/" + f.fieldName() + "]");
        }
        return build(copy, labels);
    }

    // ---------- 查询 API（签名按契约 §3.3） ----------

    /** 原始顺序的全量字段（不可变）。 */
    public List<ListField> fields() {
        return fields;
    }

    /** 必填优先排序：required=true 排前；同为必填按分区顺序稳定排序。 */
    public List<ListField> requiredFirst() {
        List<ListField> sorted = new ArrayList<>(fields);
        // List.sort 为稳定排序：同必填、同分区保持 CSV 原始顺序。
        sorted.sort(Comparator
                .comparing((ListField f) -> !f.requiredFlag())
                .thenComparingInt(f -> sectionRank(f.section())));
        return Collections.unmodifiableList(sorted);
    }

    public int size() {
        return fields.size();
    }

    /** 按 (section, fieldName) 唯一键精确查找；不同分区同名互不干扰。 */
    public Optional<ListField> find(String section, String fieldName) {
        ListField probe = new ListField(section, fieldName, "", "", false, "");
        return Optional.ofNullable(index.get(probe.uniqueKey()));
    }

    /** 某分区内的全部字段（保持原始顺序）。 */
    public List<ListField> inSection(String section) {
        List<ListField> list = bySection.get(section);
        return list == null ? List.of() : list;
    }

    /** 四分区项数（按声明顺序的不可变映射）。 */
    public Map<String, Integer> sectionCounts() {
        return sectionCounts;
    }

    // ---------- 内部：CSV 解析 ----------

    private static ListFieldDictionary parse(String raw) {
        List<RawRecord> records = parseRecords(raw);
        List<RawRecord> nonBlank = new ArrayList<>();
        for (RawRecord r : records) {
            if (r.hasContent()) {
                nonBlank.add(r);
            }
        }
        if (nonBlank.isEmpty()) {
            throw new IllegalArgumentException("字段字典 CSV 为空");
        }

        RawRecord header = nonBlank.get(0);
        if (header.cells.size() != 6
                || !HEADER_SECTION.equalsIgnoreCase(header.cells.get(0).trim())
                || !HEADER_FIELD_NAME.equalsIgnoreCase(header.cells.get(1).trim())) {
            throw new IllegalArgumentException("字段字典 CSV 表头不符，期望 "
                    + "section,field_name,field_code,value_type,required_flag,basis_note");
        }

        List<ListField> rows = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 1; i < nonBlank.size(); i++) {
            RawRecord r = nonBlank.get(i);
            List<String> c = r.cells;
            if (c.size() != 6) {
                throw new IllegalArgumentException("第" + r.line + "行 列数不符：期望 6 列，实际 " + c.size() + " 列");
            }
            String section = c.get(0).trim();
            String fieldName = c.get(1).trim();
            String fieldCode = c.get(2).trim();
            String valueType = c.get(3).trim();
            boolean required = parseRequired(c.get(4).trim(), r.line);
            String basisNote = c.get(5).trim();
            rows.add(new ListField(section, fieldName, fieldCode, valueType, required, basisNote));
            labels.add("第" + r.line + "行[" + section + "/" + fieldName + "]");
        }
        return build(rows, labels);
    }

    private static boolean parseRequired(String raw, int line) {
        if ("true".equalsIgnoreCase(raw)) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw)) {
            return false;
        }
        throw new IllegalArgumentException("第" + line + "行 required_flag 必须为布尔值 true/false，收到：" + raw);
    }

    /** 字符级 RFC4180 解析：支持双引号包裹、字段内逗号、"" 转义、引号内换行；去掉 UTF-8 BOM。 */
    private static List<RawRecord> parseRecords(String raw) {
        String text = raw == null ? "" : raw;
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        List<RawRecord> out = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        boolean fieldTouched = false;
        int line = 1;
        int recordStartLine = 1;
        int i = 0;
        final int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        cur.append('"');
                        i += 2;
                        continue;
                    }
                    inQuotes = false;
                    i++;
                    continue;
                }
                cur.append(c);
                if (c == '\n') {
                    line++;
                }
                i++;
                continue;
            }
            if (c == '"' && !fieldTouched) {
                inQuotes = true;
                fieldTouched = true;
                i++;
                continue;
            }
            if (c == ',') {
                cells.add(cur.toString());
                cur.setLength(0);
                fieldTouched = false;
                i++;
                continue;
            }
            if (c == '\r' || c == '\n') {
                cells.add(cur.toString());
                cur.setLength(0);
                fieldTouched = false;
                out.add(new RawRecord(recordStartLine, new ArrayList<>(cells)));
                cells.clear();
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') {
                    i++;
                }
                i++;
                line++;
                recordStartLine = line;
                continue;
            }
            cur.append(c);
            fieldTouched = true;
            i++;
        }
        if (fieldTouched || !cells.isEmpty() || cur.length() > 0) {
            cells.add(cur.toString());
            out.add(new RawRecord(recordStartLine, new ArrayList<>(cells)));
        }
        return out;
    }

    private static String readAll(Reader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[4096];
        int len;
        while ((len = reader.read(buf)) != -1) {
            sb.append(buf, 0, len);
        }
        return sb.toString();
    }

    // ---------- 内部：索引与强校验（CSV / JDBC 共用） ----------

    private static ListFieldDictionary build(List<ListField> rows, List<String> labels) {
        Map<String, ListField> index = new LinkedHashMap<>();
        Map<String, String> firstLabel = new LinkedHashMap<>();
        Map<String, List<ListField>> bySection = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String s : SECTION_ORDER) {
            bySection.put(s, new ArrayList<>());
            counts.put(s, 0);
        }

        for (int i = 0; i < rows.size(); i++) {
            ListField f = rows.get(i);
            String label = i < labels.size()
                    ? labels.get(i)
                    : ("第" + (i + 1) + "条[" + f.section() + "/" + f.fieldName() + "]");
            validateField(f, label);
            String key = f.uniqueKey();
            if (index.containsKey(key)) {
                throw new IllegalArgumentException("字段字典存在重复键 (section+field_name)："
                        + firstLabel.get(key) + " 与 " + label + " 冲突，拒绝静默取第一条");
            }
            index.put(key, f);
            firstLabel.put(key, label);
            bySection.get(f.section()).add(f);
            counts.merge(f.section(), 1, Integer::sum);
        }
        return new ListFieldDictionary(rows, index, bySection, counts);
    }

    private static void validateField(ListField f, String label) {
        if (f == null) {
            throw new IllegalArgumentException(label + " 字段为 null");
        }
        if (f.fieldName().isBlank()) {
            throw new IllegalArgumentException(label + " field_name 不可为空");
        }
        if (!SECTIONS.contains(f.section())) {
            throw new IllegalArgumentException(label + " section 不在四分区枚举内：" + f.section());
        }
        if (!VALUE_TYPES.contains(f.valueType())) {
            throw new IllegalArgumentException(label + " value_type 非法（期望 字符/数值/日期/代码）：" + f.valueType());
        }
    }

    private static int sectionRank(String section) {
        int i = SECTION_ORDER.indexOf(section);
        return i < 0 ? SECTION_ORDER.size() : i;
    }

    /** 一条 CSV 记录：起始行号 + 字段列表。 */
    private static final class RawRecord {
        private final int line;
        private final List<String> cells;

        private RawRecord(int line, List<String> cells) {
            this.line = line;
            this.cells = cells;
        }

        private boolean hasContent() {
            for (String c : cells) {
                if (c != null && !c.isBlank()) {
                    return true;
                }
            }
            return false;
        }
    }
}
