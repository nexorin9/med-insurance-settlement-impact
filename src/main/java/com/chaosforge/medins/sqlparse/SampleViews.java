package com.chaosforge.medins.sqlparse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 12 段脱敏样例视图 SQL 的统一加载入口（classpath 资源）。 */
public final class SampleViews {

    /** 固定顺序，golden 回归与规模测试共用。 */
    public static final List<String> VIEW_NAMES = List.of(
            "v_bill_fee",
            "v_bill_fee_agg",
            "v_bill_fee_window",
            "v_inpatient_diag",
            "v_patient_basic",
            "v_fee_detail_union",
            "v_fee_detail_union_bad",
            "v_dept_cost_cte",
            "v_cost_subquery",
            "v_settlement_star",
            "v_group_pos",
            "v_filter_matrix");

    private SampleViews() { }

    public static String resourcePath(String viewName) {
        return "samples/views/" + viewName + ".sql";
    }

    /** 读取单段样例 SQL；缺失时抛出带文件名的 IOException。 */
    public static String load(String viewName) throws IOException {
        String path = resourcePath(viewName);
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IOException("样例视图 SQL 不存在：" + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 按固定顺序读取全部 12 段。 */
    public static Map<String, String> loadAll() throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : VIEW_NAMES) out.put(name, load(name));
        return out;
    }

    public static int count() { return VIEW_NAMES.size(); }
}
