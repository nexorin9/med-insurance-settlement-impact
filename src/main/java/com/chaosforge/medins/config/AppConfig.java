package com.chaosforge.medins.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 应用配置 —— 三条出站链路的端点与凭据，全部来自环境变量。
 *
 * <p>三条链路：① 院内 HIS/EMR 只读库（JDBC）；② 院内接口管理平台（HTTP 读 + 幂等回写）；
 * ③ 模型端点（OpenAI-compatible）。
 *
 * <p>规则：
 * <ul>
 *   <li>凭据与端点**只从环境变量读**，不读配置文件、不接受命令行明文参数、不写盘；</li>
 *   <li>缺失即**显式报错**（{@link ConfigException}），错误信息只报变量名、不报值，绝不静默填默认值；</li>
 *   <li>{@link #MODEL_TIMEOUT_SECONDS} 是唯一的可默认项（默认 30 秒），且用了默认值会被显式记录在
 *       {@link #defaultsApplied()} 里，由自检命令展示出来 —— 不是静默兜底；</li>
 *   <li>{@link #toString()} 与 {@link #redactor()} 保证凭据字面量不进日志。</li>
 * </ul>
 */
public final class AppConfig {

    // ---- ① 院内 HIS / EMR 只读库 ----
    public static final String ENV_HIS_DB_URL = "HIS_DB_URL";
    public static final String ENV_HIS_DB_USER = "HIS_DB_USER";
    public static final String ENV_HIS_DB_PASSWORD = "HIS_DB_PASSWORD";

    // ---- ② 院内接口管理平台 ----
    public static final String ENV_IFACE_REGISTRY_BASE_URL = "IFACE_REGISTRY_BASE_URL";
    public static final String ENV_IFACE_REGISTRY_TOKEN = "IFACE_REGISTRY_TOKEN";

    // ---- ③ 模型端点 ----
    public static final String ENV_MODEL_BASE_URL = "MODEL_BASE_URL";
    public static final String ENV_MODEL_API_KEY = "MODEL_API_KEY";
    public static final String ENV_MODEL_NAME = "MODEL_NAME";
    public static final String ENV_MODEL_TIMEOUT_SECONDS = "MODEL_TIMEOUT_SECONDS";

    /** 模型读超时的默认值（秒）。使用默认值时会被显式记录。 */
    public static final int DEFAULT_MODEL_TIMEOUT_SECONDS = 30;

    /** 全部需要读取的环境变量名（按声明顺序）。 */
    public static final List<String> ALL_VARIABLES = List.of(
            ENV_HIS_DB_URL,
            ENV_HIS_DB_USER,
            ENV_HIS_DB_PASSWORD,
            ENV_IFACE_REGISTRY_BASE_URL,
            ENV_IFACE_REGISTRY_TOKEN,
            ENV_MODEL_BASE_URL,
            ENV_MODEL_API_KEY,
            ENV_MODEL_NAME,
            ENV_MODEL_TIMEOUT_SECONDS);

    private final String hisDbUrl;
    private final String hisDbUser;
    private final String hisDbPassword;
    private final String ifaceRegistryBaseUrl;
    private final String ifaceRegistryToken;
    private final String modelBaseUrl;
    private final String modelApiKey;
    private final String modelName;
    private final int modelTimeoutSeconds;
    private final List<String> defaultsApplied;
    private final SecretRedactor redactor;

    private AppConfig(Map<String, String> values, int modelTimeoutSeconds, List<String> defaultsApplied) {
        this.hisDbUrl = values.get(ENV_HIS_DB_URL);
        this.hisDbUser = values.get(ENV_HIS_DB_USER);
        this.hisDbPassword = values.get(ENV_HIS_DB_PASSWORD);
        this.ifaceRegistryBaseUrl = values.get(ENV_IFACE_REGISTRY_BASE_URL);
        this.ifaceRegistryToken = values.get(ENV_IFACE_REGISTRY_TOKEN);
        this.modelBaseUrl = values.get(ENV_MODEL_BASE_URL);
        this.modelApiKey = values.get(ENV_MODEL_API_KEY);
        this.modelName = values.get(ENV_MODEL_NAME);
        this.modelTimeoutSeconds = modelTimeoutSeconds;
        this.defaultsApplied = List.copyOf(defaultsApplied);
        this.redactor = SecretRedactor.of(List.of(hisDbPassword, ifaceRegistryToken, modelApiKey));
    }

    /**
     * 从环境变量加载配置。
     *
     * @param env 环境变量来源
     * @throws ConfigException 必需变量缺失，或超时取值非法
     */
    public static AppConfig load(EnvSource env) {
        Map<String, String> values = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();

        for (String name : ALL_VARIABLES) {
            if (ENV_MODEL_TIMEOUT_SECONDS.equals(name)) {
                continue;
            }
            String raw = env.get(name);
            if (raw == null || raw.isBlank()) {
                missing.add(name);
            } else {
                values.put(name, raw.trim());
            }
        }

        if (!missing.isEmpty()) {
            throw new ConfigException(missing);
        }

        List<String> defaultsApplied = new ArrayList<>();
        int timeout = DEFAULT_MODEL_TIMEOUT_SECONDS;
        String rawTimeout = env.get(ENV_MODEL_TIMEOUT_SECONDS);
        if (rawTimeout == null || rawTimeout.isBlank()) {
            defaultsApplied.add(ENV_MODEL_TIMEOUT_SECONDS);
        } else {
            try {
                timeout = Integer.parseInt(rawTimeout.trim());
            } catch (NumberFormatException e) {
                throw new ConfigException(ENV_MODEL_TIMEOUT_SECONDS, "必须是整数秒");
            }
            if (timeout <= 0) {
                throw new ConfigException(ENV_MODEL_TIMEOUT_SECONDS, "必须大于 0");
            }
        }

        return new AppConfig(values, timeout, defaultsApplied);
    }

    /**
     * 只检查、不抛错的体检视图 —— 供 {@code doctor} 子命令逐项汇报「哪个变量还没设」。
     *
     * @param env 环境变量来源
     */
    public static List<EnvVarStatus> inspect(EnvSource env) {
        List<EnvVarStatus> status = new ArrayList<>();
        for (String name : ALL_VARIABLES) {
            boolean present = env.has(name);
            boolean defaulted = false;
            String note = "";
            if (ENV_MODEL_TIMEOUT_SECONDS.equals(name)) {
                defaulted = !present;
                note = defaulted
                        ? "未设置，将使用默认值 " + DEFAULT_MODEL_TIMEOUT_SECONDS + " 秒"
                        : "已设置";
            } else {
                note = present ? "已设置" : "未设置";
            }
            status.add(new EnvVarStatus(name, present, defaulted, note));
        }
        return status;
    }

    /** 院内 HIS/EMR 只读库连接串。 */
    public String hisDbUrl() {
        return hisDbUrl;
    }

    /** 院内 HIS/EMR 只读库账号（只读账号）。 */
    public String hisDbUser() {
        return hisDbUser;
    }

    /** 院内 HIS/EMR 只读库口令。 */
    public String hisDbPassword() {
        return hisDbPassword;
    }

    /** 院内接口管理平台基址。 */
    public String ifaceRegistryBaseUrl() {
        return ifaceRegistryBaseUrl;
    }

    /** 院内接口管理平台 Bearer 令牌。 */
    public String ifaceRegistryToken() {
        return ifaceRegistryToken;
    }

    /** 模型端点基址。 */
    public String modelBaseUrl() {
        return modelBaseUrl;
    }

    /** 模型端点 API Key。 */
    public String modelApiKey() {
        return modelApiKey;
    }

    /** 模型名。 */
    public String modelName() {
        return modelName;
    }

    /** 模型读超时秒数。 */
    public int modelTimeoutSeconds() {
        return modelTimeoutSeconds;
    }

    /** 实际使用了默认值的变量名；正常情况下只有超时项可能出现。 */
    public List<String> defaultsApplied() {
        return Collections.unmodifiableList(defaultsApplied);
    }

    /** 凭据脱敏器：日志与异常出栈前统一过一遍。 */
    public SecretRedactor redactor() {
        return redactor;
    }

    /** 脱敏后的配置概览；不含任何凭据字面量。 */
    @Override
    public String toString() {
        return "AppConfig{"
                + ENV_HIS_DB_URL + "=" + hisDbUrl
                + ", " + ENV_HIS_DB_USER + "=" + hisDbUser
                + ", " + ENV_HIS_DB_PASSWORD + "=" + SecretRedactor.MASK
                + ", " + ENV_IFACE_REGISTRY_BASE_URL + "=" + ifaceRegistryBaseUrl
                + ", " + ENV_IFACE_REGISTRY_TOKEN + "=" + SecretRedactor.MASK
                + ", " + ENV_MODEL_BASE_URL + "=" + modelBaseUrl
                + ", " + ENV_MODEL_API_KEY + "=" + SecretRedactor.MASK
                + ", " + ENV_MODEL_NAME + "=" + modelName
                + ", " + ENV_MODEL_TIMEOUT_SECONDS + "=" + modelTimeoutSeconds
                + (defaultsApplied.isEmpty() ? "" : ", 使用默认值的变量=" + defaultsApplied)
                + "}";
    }

    /**
     * 单个环境变量的体检结果。
     *
     * @param name      变量名
     * @param present   是否已设置
     * @param defaulted 是否使用了默认值（仅超时项可能为真）
     * @param note      中文说明
     */
    public record EnvVarStatus(String name, boolean present, boolean defaulted, String note) {
    }
}
