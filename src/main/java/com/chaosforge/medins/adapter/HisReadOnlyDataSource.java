package com.chaosforge.medins.adapter;

import com.chaosforge.medins.config.AppConfig;
import com.chaosforge.medins.config.EnvSource;
import com.chaosforge.medins.config.SecretRedactor;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * HIS/EMR 只读库数据源 —— 取数视图定义与清单字段字典的唯一权威来源。
 *
 * <p>硬性规则：
 * <ul>
 *   <li>连接参数只从 {@code HIS_DB_URL / HIS_DB_USER / HIS_DB_PASSWORD} 三个环境变量读，不硬编码、不读配置文件；</li>
 *   <li>缺任一项即报错，且只指出变量名，不回显取值；</li>
 *   <li>口令不写进连接串字面量日志，异常／日志统一过 {@link SecretRedactor}；</li>
 *   <li>连接失败区分「网络不可达 / 账号口令错误 / 库不存在」三类并给可操作提示，
 *       <b>绝不静默降级为本地文件，也不返回空结果</b>；</li>
 *   <li>单机工具，不做连接池框架，每次 {@link #open()} 由 JDBC 驱动直连。</li>
 * </ul>
 *
 * <p>{@code doctor} 自检所需的「JDK 版本 / 驱动可用性 / 凭据是否已设」检查以静态方法提供。
 */
public final class HisReadOnlyDataSource {

    public static final String ENV_URL = AppConfig.ENV_HIS_DB_URL;
    public static final String ENV_USER = AppConfig.ENV_HIS_DB_USER;
    public static final String ENV_PASSWORD = AppConfig.ENV_HIS_DB_PASSWORD;

    private final String url;
    private final String user;
    private final String password;
    private final SecretRedactor redactor;

    private HisReadOnlyDataSource(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
        // 登记口令字面量，使任何拼进口令的文本都能被替换
        List<String> secrets = new ArrayList<>();
        if (password != null) {
            secrets.add(password);
        }
        this.redactor = SecretRedactor.of(secrets);
    }

    /** 从进程环境变量加载；缺凭据抛 {@link HisDataSourceException}，只报变量名。 */
    public static HisReadOnlyDataSource fromEnv() {
        return fromEnv(EnvSource.system());
    }

    /** 可注入环境来源的重载，供测试与离线自检使用（不读进程真实环境）。 */
    public static HisReadOnlyDataSource fromEnv(EnvSource env) {
        String u = env.get(ENV_URL);
        String usr = env.get(ENV_USER);
        String pwd = env.get(ENV_PASSWORD);
        List<String> missing = new ArrayList<>();
        if (isBlank(u)) {
            missing.add(ENV_URL);
        }
        if (isBlank(usr)) {
            missing.add(ENV_USER);
        }
        if (isBlank(pwd)) {
            missing.add(ENV_PASSWORD);
        }
        if (!missing.isEmpty()) {
            String names = String.join("、", missing);
            throw new HisDataSourceException(
                    "缺少必需的环境变量：" + names + "（只报变量名，不回显取值）",
                    HisDataSourceException.FailureKind.MISSING_CREDENTIAL,
                    "请在系统环境或 .env 中补齐：" + names);
        }
        return new HisReadOnlyDataSource(u.trim(), usr.trim(), pwd.trim());
    }

    /** 供测试直接构造（不依赖系统已设凭据）。 */
    public static HisReadOnlyDataSource of(String url, String user, String password) {
        return new HisReadOnlyDataSource(url, user, password);
    }

    /**
     * 打开只读连接。口令以参数形式传给 JDBC，不拼进 URL 字面量。
     * 失败按三类原因归类并给出可操作提示，异常消息脱敏。
     */
    public Connection open() throws HisDataSourceException {
        try {
            return DriverManager.getConnection(url, user, password);
        } catch (SQLException e) {
            HisDataSourceException.FailureKind kind = classify(e);
            String detail = redactor.redact(String.valueOf(e.getMessage()));
            String message = "连接院内只读库失败【" + kind.labelZh() + "】：" + kind.hintZh()
                    + "；错误摘要（已脱敏）：" + detail;
            throw new HisDataSourceException(message, kind, kind.hintZh(), e);
        }
    }

    /** 不含口令的安全连接串标识，可作审计的 sourceId。 */
    public String safeUrl() {
        return redactor.redact(url == null ? "" : url);
    }

    /** 库地址标识（不含口令），供 ViewDefinition.sourceId 使用。 */
    public String sourceId() {
        return safeUrl();
    }

    // ---- doctor 自检静态方法 ----

    /** JDK 版本体检：只报版本与是否满足 17。 */
    public static String doctorJdkVersion() {
        String version = System.getProperty("java.version", "unknown");
        int major = majorVersion(version);
        return "Java " + version + "（需要 17 及以上，" + (major >= 17 ? "满足" : "不满足") + "）";
    }

    /** Oracle JDBC 驱动是否在运行 classpath 中。 */
    public static boolean driverAvailable() {
        try {
            Class.forName("oracle.jdbc.OracleDriver");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** 凭据是否已设：只报变量名与「已设置／未设置」，绝不回显取值。 */
    public static List<String> doctorCredentialStatus(EnvSource env) {
        List<String> out = new ArrayList<>();
        for (String name : List.of(ENV_URL, ENV_USER, ENV_PASSWORD)) {
            out.add(name + "=" + (isBlank(env.get(name)) ? "未设置" : "已设置"));
        }
        return out;
    }

    /** 汇总自检报告（JDK 版本 / 驱动可用性 / 凭据是否已设）。 */
    public static String doctorReport(EnvSource env) {
        StringBuilder sb = new StringBuilder();
        sb.append(doctorJdkVersion()).append(System.lineSeparator());
        sb.append("只读库驱动 oracle.jdbc.OracleDriver：")
                .append(driverAvailable() ? "可用" : "不可用（请把驱动加入 classpath）")
                .append(System.lineSeparator());
        for (String line : doctorCredentialStatus(env)) {
            sb.append(line).append(System.lineSeparator());
        }
        return sb.toString();
    }

    /**
     * 把一条 SQLException 归类到三类可操作原因之一（外加驱动不可用／未知）。
     * 分类只看错误文本与 SQLState，绝不回显口令。
     */
    public static HisDataSourceException.FailureKind classify(SQLException e) {
        if (e == null) {
            return HisDataSourceException.FailureKind.UNKNOWN;
        }
        return classifyRaw(String.valueOf(e.getMessage()) + " " + String.valueOf(e.getSQLState()));
    }

    /** 直接按（已可脱敏的）错误文本归类，便于测试与日志复用。 */
    public static HisDataSourceException.FailureKind classifyRaw(String raw) {
        String s = (raw == null ? "" : raw.toLowerCase(Locale.ROOT));
        // 驱动缺失优先判定
        if (has(s, "no suitable driver", "classnotfound", "driver class", "找不到驱动")) {
            return HisDataSourceException.FailureKind.DRIVER_UNAVAILABLE;
        }
        // 网络不可达
        if (has(s, "connection refused", "io error", "network is unreachable", "unknown host",
                "timed out", "connect timeout", "connection timed out", "no route",
                "the network adapter", "socket", "refused", "ora-12541", "ora-12514",
                "ora-12170", "08001", "08006", "08s01", "无法连接", "网络不可达")) {
            return HisDataSourceException.FailureKind.NETWORK_UNREACHABLE;
        }
        // 账号口令错误
        if (has(s, "ora-01017", "invalid username", "invalid password", "invalid username/password",
                "authentication failed", "login failed", "access denied", "28000", "密码错误",
                "账号口令错误", "authentication", "logon denied")) {
            return HisDataSourceException.FailureKind.AUTH_FAILED;
        }
        // 库 / 服务名不存在
        if (has(s, "ora-12505", "does not currently know of sid", "service name", "sid",
                "database does not exist", "database not found", "unknown database",
                "no database", "库不存在", "08004")) {
            return HisDataSourceException.FailureKind.DB_NOT_FOUND;
        }
        return HisDataSourceException.FailureKind.UNKNOWN;
    }

    /** 构造脱敏后的可操作连接失败提示。 */
    public static String actionableMessage(SQLException e, SecretRedactor redactor) {
        HisDataSourceException.FailureKind kind = classify(e);
        String detail = redactor.redact(String.valueOf(e == null ? "" : e.getMessage()));
        return "【" + kind.labelZh() + "】" + kind.hintZh() + "；错误摘要（已脱敏）：" + detail;
    }

    private static boolean has(String text, String... needles) {
        for (String n : needles) {
            if (text.contains(n)) {
                return true;
            }
        }
        return false;
    }

    private static int majorVersion(String version) {
        try {
            String head = version.split("[.\\-+]")[0];
            return Integer.parseInt(head);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
