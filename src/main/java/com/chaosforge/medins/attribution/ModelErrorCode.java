package com.chaosforge.medins.attribution;

/**
 * 模型调用六类错误码（固定归一）。
 *
 * <p>连接超时 / 读超时 / HTTP 4xx / HTTP 5xx / 空响应 / JSON 不合 schema，
 * 各自映射到唯一错误码。仅 {@link #MODEL_CONNECT_TIMEOUT}、{@link #MODEL_READ_TIMEOUT}、
 * {@link #MODEL_HTTP_5XX} 做有限退避重试；4xx 与 schema 不合规不重试（重试也不会变好）。
 */
public enum ModelErrorCode {
    MODEL_CONNECT_TIMEOUT,
    MODEL_READ_TIMEOUT,
    MODEL_HTTP_4XX,
    MODEL_HTTP_5XX,
    MODEL_EMPTY_RESPONSE,
    MODEL_SCHEMA_INVALID
}
