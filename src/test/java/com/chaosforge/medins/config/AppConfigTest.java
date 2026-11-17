package com.chaosforge.medins.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置层测试 —— 验证「凭据只从环境变量读、缺失显式报错、日志脱敏」三条硬规则。
 */
class AppConfigTest {

    private static final String PASSWORD = "S3cret-Passw0rd";
    private static final String TOKEN = "bearer-token-abcdef123456";
    private static final String API_KEY = "sk-model-key-9876543210";

    private static Map<String, String> fullEnv() {
        Map<String, String> env = new HashMap<>();
        env.put(AppConfig.ENV_HIS_DB_URL, "jdbc:oracle:thin:@//db-host:1521/ORCL");
        env.put(AppConfig.ENV_HIS_DB_USER, "readonly_user");
        env.put(AppConfig.ENV_HIS_DB_PASSWORD, PASSWORD);
        env.put(AppConfig.ENV_IFACE_REGISTRY_BASE_URL, "http://registry-host:8080");
        env.put(AppConfig.ENV_IFACE_REGISTRY_TOKEN, TOKEN);
        env.put(AppConfig.ENV_MODEL_BASE_URL, "http://model-host:9000");
        env.put(AppConfig.ENV_MODEL_API_KEY, API_KEY);
        env.put(AppConfig.ENV_MODEL_NAME, "hospital-llm");
        return env;
    }

    @Test
    @DisplayName("缺凭据时显式报错，并指出缺哪个变量名")
    void missingCredentialFailsLoudly() {
        Map<String, String> env = fullEnv();
        env.remove(AppConfig.ENV_HIS_DB_PASSWORD);

        ConfigException error = assertThrows(ConfigException.class, () -> AppConfig.load(EnvSource.of(env)));

        assertTrue(error.missingVariables().contains(AppConfig.ENV_HIS_DB_PASSWORD));
        assertTrue(error.getMessage().contains(AppConfig.ENV_HIS_DB_PASSWORD));
    }

    @Test
    @DisplayName("错误信息与异常文本都不含任何凭据字面量")
    void errorMessageCarriesNoSecretLiteral() {
        Map<String, String> env = fullEnv();
        env.remove(AppConfig.ENV_MODEL_API_KEY);

        ConfigException error = assertThrows(ConfigException.class, () -> AppConfig.load(EnvSource.of(env)));

        String message = error.getMessage();
        assertFalse(message.contains(PASSWORD));
        assertFalse(message.contains(TOKEN));
        assertFalse(message.contains(API_KEY));
    }

    @Test
    @DisplayName("配置齐全时正常加载，超时项用默认值会被显式记录")
    void loadsWithExplicitDefaultNotice() {
        AppConfig config = AppConfig.load(EnvSource.of(fullEnv()));

        assertEquals("jdbc:oracle:thin:@//db-host:1521/ORCL", config.hisDbUrl());
        assertEquals("readonly_user", config.hisDbUser());
        assertEquals(PASSWORD, config.hisDbPassword());
        assertEquals("http://registry-host:8080", config.ifaceRegistryBaseUrl());
        assertEquals("hospital-llm", config.modelName());
        assertEquals(AppConfig.DEFAULT_MODEL_TIMEOUT_SECONDS, config.modelTimeoutSeconds());
        assertTrue(config.defaultsApplied().contains(AppConfig.ENV_MODEL_TIMEOUT_SECONDS));
    }

    @Test
    @DisplayName("超时项显式设置时使用设置值，且不记为默认")
    void explicitTimeoutWins() {
        Map<String, String> env = fullEnv();
        env.put(AppConfig.ENV_MODEL_TIMEOUT_SECONDS, "45");

        AppConfig config = AppConfig.load(EnvSource.of(env));

        assertEquals(45, config.modelTimeoutSeconds());
        assertTrue(config.defaultsApplied().isEmpty());
    }

    @Test
    @DisplayName("超时项取值非法时报错并指出变量名，不回显取值")
    void invalidTimeoutRejected() {
        Map<String, String> env = fullEnv();
        env.put(AppConfig.ENV_MODEL_TIMEOUT_SECONDS, "not-a-number");

        ConfigException error = assertThrows(ConfigException.class, () -> AppConfig.load(EnvSource.of(env)));

        assertEquals(AppConfig.ENV_MODEL_TIMEOUT_SECONDS, error.variableName());
        assertFalse(error.getMessage().contains("not-a-number"));
    }

    @Test
    @DisplayName("toString 不含口令、令牌与 API Key 字面量")
    void toStringHidesSecrets() {
        AppConfig config = AppConfig.load(EnvSource.of(fullEnv()));

        String text = config.toString();

        assertFalse(text.contains(PASSWORD));
        assertFalse(text.contains(TOKEN));
        assertFalse(text.contains(API_KEY));
        assertTrue(text.contains(SecretRedactor.MASK));
    }

    @Test
    @DisplayName("脱敏器抹掉已登记凭据与常见凭据模式")
    void redactorMasksSecretsAndPatterns() {
        AppConfig config = AppConfig.load(EnvSource.of(fullEnv()));
        SecretRedactor redactor = config.redactor();

        String text = "连接串口令 " + PASSWORD + "，请求头 Authorization: Bearer " + TOKEN
                + "，另有 password=plain-text 与 api_key=" + API_KEY;
        String redacted = redactor.redact(text);

        assertFalse(redacted.contains(PASSWORD));
        assertFalse(redacted.contains(TOKEN));
        assertFalse(redacted.contains(API_KEY));
        assertFalse(redacted.contains("plain-text"));
        assertTrue(redacted.contains(SecretRedactor.MASK));
    }

    @Test
    @DisplayName("doctor 自检逐项报出变量是否已设，不回显取值")
    void inspectReportsPresenceWithoutValues() {
        Map<String, String> env = fullEnv();
        env.remove(AppConfig.ENV_IFACE_REGISTRY_TOKEN);

        List<AppConfig.EnvVarStatus> statuses = AppConfig.inspect(EnvSource.of(env));

        assertEquals(AppConfig.ALL_VARIABLES.size(), statuses.size());
        AppConfig.EnvVarStatus tokenStatus = statuses.stream()
                .filter(s -> s.name().equals(AppConfig.ENV_IFACE_REGISTRY_TOKEN))
                .findFirst()
                .orElseThrow();
        assertFalse(tokenStatus.present());
        assertFalse(tokenStatus.note().contains(TOKEN));
        assertDoesNotThrow(() -> statuses.forEach(s -> assertTrue(s.name().startsWith("HIS_")
                || s.name().startsWith("IFACE_") || s.name().startsWith("MODEL_"))));
    }
}
