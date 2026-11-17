package com.chaosforge.medins.config;

import java.util.Collections;
import java.util.List;

/**
 * 配置异常 —— 凭据缺失或取值非法时抛出。
 *
 * <p>异常消息里**只出现变量名**，绝不出现任何凭据值；这样错误可以直接贴进工单或微信群里，
 * 又不会把口令、令牌、API Key 泄出去。
 */
public class ConfigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final List<String> missingVariables;
    private final String variableName;

    /**
     * 多个变量缺失。
     *
     * @param missingVariables 缺失的变量名列表（按声明顺序）
     */
    public ConfigException(List<String> missingVariables) {
        super("缺少必需的环境变量：" + String.join("、", missingVariables)
                + "；请在系统环境或 .env 中设置后重试（错误信息不含变量值）");
        this.missingVariables = List.copyOf(missingVariables);
        this.variableName = "";
    }

    /**
     * 单个变量取值非法。
     *
     * @param variableName 变量名
     * @param reason       非法原因（不得包含变量值）
     */
    public ConfigException(String variableName, String reason) {
        super("环境变量 " + variableName + " 取值非法：" + reason);
        this.missingVariables = List.of(variableName);
        this.variableName = variableName;
    }

    /** 缺失的变量名列表。 */
    public List<String> missingVariables() {
        return Collections.unmodifiableList(missingVariables);
    }

    /** 出问题的变量名；批量缺失时为空字符串。 */
    public String variableName() {
        return variableName;
    }
}
