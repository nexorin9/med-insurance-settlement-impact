package com.chaosforge.medins.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 凭据脱敏器 —— 保证口令、令牌、API Key 的字面量**不会出现在日志、异常与界面文本里**。
 *
 * <p>两条脱敏路径：
 * <ol>
 *   <li><b>登记式</b>：配置加载后把口令／令牌／API Key 登记进来，之后任何文本里出现这些字面量一律替换为
 *       {@code ***}。这是主路径，覆盖「凭据被拼进异常消息」这类最常见的泄漏；</li>
 *   <li><b>模式式</b>：无论凭据是否登记，{@code Bearer xxxxx}、{@code password=xxxxx}、连接串里的口令段
 *       一律替换为 {@code ***}。</li>
 * </ol>
 *
 * <p>本类不可变且线程安全，可作为单例在各处共享。
 */
public final class SecretRedactor {

    /** 脱敏后的占位符。 */
    public static final String MASK = "***";

    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)(bearer\\s+)[^\\s\"',;]+");
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("(?i)(password\\s*[=:]\\s*)[^\\s\"',;]+");
    private static final Pattern JDBC_INLINE_PASSWORD_PATTERN =
            Pattern.compile("(?i)(://[^:/@\\s]+:)([^@\\s]+)(@)");
    private static final Pattern TOKEN_PATTERN = Pattern.compile("(?i)((?:api[_-]?key|token|secret)\\s*[=:]\\s*)[^\\s\"',;]+");

    private final List<String> registeredSecrets;

    private SecretRedactor(List<String> registeredSecrets) {
        this.registeredSecrets = List.copyOf(registeredSecrets);
    }

    /**
     * 用一组凭据字面量构造脱敏器。
     *
     * @param secrets 凭据字面量；空白项忽略，短于 4 字的忽略（太短，误伤正常文本的概率高于收益）
     */
    public static SecretRedactor of(Collection<String> secrets) {
        List<String> kept = new ArrayList<>();
        if (secrets != null) {
            for (String secret : secrets) {
                if (secret == null) {
                    continue;
                }
                String trimmed = secret.trim();
                if (trimmed.length() >= 4) {
                    kept.add(trimmed);
                }
            }
        }
        // 长字面量优先替换，避免短字面量先命中导致长串被切成两半
        kept.sort((a, b) -> Integer.compare(b.length(), a.length()));
        return new SecretRedactor(kept);
    }

    /** 空脱敏器：只做模式式脱敏。 */
    public static SecretRedactor empty() {
        return new SecretRedactor(List.of());
    }

    /**
     * 脱敏一段文本；{@code null} 原样返回。
     */
    public String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (String secret : registeredSecrets) {
            result = result.replace(secret, MASK);
        }
        result = BEARER_PATTERN.matcher(result).replaceAll("$1" + MASK);
        result = PASSWORD_PATTERN.matcher(result).replaceAll("$1" + MASK);
        result = TOKEN_PATTERN.matcher(result).replaceAll("$1" + MASK);
        result = JDBC_INLINE_PASSWORD_PATTERN.matcher(result).replaceAll("$1" + MASK + "$3");
        return result;
    }

    /**
     * 脱敏一行日志消息，附加行尾换行。
     */
    public String redactLine(String message) {
        return redact(message) + System.lineSeparator();
    }

    /** 已登记的凭据数量（供自检报告用，不回显内容）。 */
    public int registeredCount() {
        return registeredSecrets.size();
    }
}
