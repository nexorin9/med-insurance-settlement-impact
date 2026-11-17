package com.chaosforge.medins.attribution;

/**
 * provider-neutral 的 HTTP 响应描述（POJO）：状态码 + 响应体文本。
 *
 * <p>HTTP 4xx / 5xx 不通过异常表达，而是以 {@code status} 返回，由网关统一归一到错误码；
 * 连接超时与读超时才由 {@link HttpExecutor} 抛专用 JDK 超时异常。
 */
public final class HttpResponse {

    private final int status;
    private final String body;

    public HttpResponse(int status, String body) {
        this.status = status;
        this.body = body == null ? "" : body;
    }

    public int status() {
        return status;
    }

    public String body() {
        return body;
    }
}
