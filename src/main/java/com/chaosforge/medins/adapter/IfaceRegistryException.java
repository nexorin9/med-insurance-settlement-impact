package com.chaosforge.medins.adapter;

/**
 * 院内接口管理平台读写异常：非 2xx 一律显式抛错，带状态码与响应摘要。
 * 不吞异常、不把失败当成功、不返回空集合冒充查无记录。
 */
public class IfaceRegistryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String bodySnippet;

    public IfaceRegistryException(String message) {
        this(message, -1, "");
    }

    public IfaceRegistryException(String message, int status, String bodySnippet) {
        super(message);
        this.status = status;
        this.bodySnippet = bodySnippet == null ? "" : bodySnippet;
    }

    public IfaceRegistryException(String message, int status, String bodySnippet, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.bodySnippet = bodySnippet == null ? "" : bodySnippet;
    }

    /** HTTP 状态码；网络类失败为 -1。 */
    public int status() { return status; }

    /** 响应体摘要（已截断、脱敏）。 */
    public String bodySnippet() { return bodySnippet; }
}
