package com.chaosforge.medins.adapter;

/**
 * HIS 只读数据源异常：凭据缺失、连接失败（三类可区分）、驱动不可用等。
 *
 * <p>异常消息一律经过脱敏，且不回显口令／令牌字面量；缺凭据时只报变量名。
 * 设计为非受检异常，使 {@link HisReadOnlyDataSource#fromEnv()} 的签名保持简洁
 * （契约 §3.4 中 fromEnv 不带 throws），而 {@code open()} 仍可显式声明。
 */
public class HisDataSourceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 连接失败的可区分原因，附中文标签与可操作提示。 */
    public enum FailureKind {
        MISSING_CREDENTIAL("凭据缺失", "请在系统环境或 .env 中补齐缺失变量后重试"),
        NETWORK_UNREACHABLE("网络不可达", "请检查 HIS_DB_URL 指向的主机与端口是否可达、防火墙与监听是否开启"),
        AUTH_FAILED("账号口令错误", "请核对 HIS_DB_USER / HIS_DB_PASSWORD（只读账号），凭据不会回显"),
        DB_NOT_FOUND("库不存在", "请确认 HIS_DB_URL 中的服务名/SID 是否正确，或联系 DBA 确认实例"),
        DRIVER_UNAVAILABLE("驱动不可用", "请把只读库的 JDBC 驱动加入运行 classpath"),
        UNKNOWN("连接失败", "请查看脱敏后的错误摘要并联系 DBA");

        private final String labelZh;
        private final String hintZh;

        FailureKind(String labelZh, String hintZh) {
            this.labelZh = labelZh;
            this.hintZh = hintZh;
        }

        public String labelZh() {
            return labelZh;
        }

        public String hintZh() {
            return hintZh;
        }
    }

    private final FailureKind kind;
    private final String actionableHint;

    public HisDataSourceException(String message, FailureKind kind, String actionableHint) {
        this(message, kind, actionableHint, null);
    }

    public HisDataSourceException(String message, FailureKind kind, String actionableHint, Throwable cause) {
        super(message, cause);
        this.kind = kind == null ? FailureKind.UNKNOWN : kind;
        this.actionableHint = actionableHint == null ? this.kind.hintZh() : actionableHint;
    }

    /** 可区分的失败原因。 */
    public FailureKind kind() {
        return kind;
    }

    /** 面向运维的可操作提示。 */
    public String actionableHint() {
        return actionableHint;
    }
}
