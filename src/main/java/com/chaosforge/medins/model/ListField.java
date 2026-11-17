package com.chaosforge.medins.model;

import java.util.Objects;

/**
 * 结算清单字段（清单字段字典中的一项）。
 *
 * <p>权威来源是本院 HIS 结算模块字段元数据表；本类的结构同时承载 JDBC 读取与 CSV 样例两条来源，
 * 保证「样例验证」与「院内实读」走同一条代码路径。
 *
 * <p>唯一键为「分区 + 字段名」；同一分区内的字段名不重复，不同分区允许同名。
 */
public final class ListField {

    /** 结算清单四分区（项数以目标统筹区执行的版本为准）。 */
    public static final String SECTION_BASIC = "基本信息";
    public static final String SECTION_OUTPATIENT_CHRONIC = "门诊慢特病诊疗信息";
    public static final String SECTION_INPATIENT = "住院诊疗信息";
    public static final String SECTION_FEE = "医疗收费信息";

    /** 字段取值类型。 */
    public static final String VALUE_TYPE_CHAR = "字符";
    public static final String VALUE_TYPE_NUMBER = "数值";
    public static final String VALUE_TYPE_DATE = "日期";
    public static final String VALUE_TYPE_CODE = "代码";

    private final String section;
    private final String fieldName;
    private final String fieldCode;
    private final String valueType;
    private final boolean requiredFlag;
    private final String basisNote;

    public ListField(String section,
                     String fieldName,
                     String fieldCode,
                     String valueType,
                     boolean requiredFlag,
                     String basisNote) {
        this.section = blankToEmpty(section);
        this.fieldName = blankToEmpty(fieldName);
        this.fieldCode = fieldCode == null ? "" : fieldCode.trim();
        this.valueType = blankToEmpty(valueType);
        this.requiredFlag = requiredFlag;
        this.basisNote = basisNote == null ? "" : basisNote.trim();
    }

    private static String blankToEmpty(String raw) {
        return raw == null ? "" : raw.trim();
    }

    /** 清单分区。 */
    public String section() {
        return section;
    }

    /** 清单字段名。 */
    public String fieldName() {
        return fieldName;
    }

    /** 字段代码，可为空。 */
    public String fieldCode() {
        return fieldCode;
    }

    /** 取值类型（字符／数值／日期／代码）。 */
    public String valueType() {
        return valueType;
    }

    /** 是否必填；必填字段的波及优先呈现。 */
    public boolean requiredFlag() {
        return requiredFlag;
    }

    /** 官方口径注释，来自清单字段规范。 */
    public String basisNote() {
        return basisNote;
    }

    /** 唯一键「分区 + 字段名」。 */
    public String uniqueKey() {
        return section + "" + fieldName;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ListField that)) {
            return false;
        }
        return requiredFlag == that.requiredFlag
                && section.equals(that.section)
                && fieldName.equals(that.fieldName)
                && fieldCode.equals(that.fieldCode)
                && valueType.equals(that.valueType)
                && basisNote.equals(that.basisNote);
    }

    @Override
    public int hashCode() {
        return Objects.hash(section, fieldName, fieldCode, valueType, requiredFlag, basisNote);
    }

    @Override
    public String toString() {
        return uniqueKey();
    }
}
