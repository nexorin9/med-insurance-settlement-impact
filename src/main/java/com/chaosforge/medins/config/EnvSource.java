package com.chaosforge.medins.config;

import java.util.Map;
import java.util.function.Function;

/**
 * 环境变量来源 —— 配置读取的唯一入口抽象。
 *
 * <p>生产实现读进程环境变量；测试注入一个固定 Map，从而不必真的改进程环境。
 * 本抽象存在的意义是让「凭据只从环境变量读」这条规则可以被单元测试直接断言。
 */
@FunctionalInterface
public interface EnvSource {

    /**
     * 取变量值；未设置时返回 {@code null}。
     *
     * @param name 环境变量名
     */
    String get(String name);

    /**
     * 变量是否已设置（非空白）。
     */
    default boolean has(String name) {
        String value = get(name);
        return value != null && !value.isBlank();
    }

    /** 进程环境变量来源。 */
    static EnvSource system() {
        return System::getenv;
    }

    /**
     * 固定映射来源，供测试与离线自检使用。
     *
     * @param values 变量名到取值的映射；取值为 {@code null} 视为未设置
     */
    static EnvSource of(Map<String, String> values) {
        Function<String, String> lookup = values::get;
        return lookup::apply;
    }
}
