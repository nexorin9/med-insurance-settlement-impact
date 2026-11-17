package com.chaosforge.medins.adapter;

/** HTTP 原始响应：状态码 + 响应体文本（已做大小上限与脱敏）。 */
public final class RawResponse {

    private final int status;
    private final String body;

    public RawResponse(int status, String body) {
        this.status = status;
        this.body = body == null ? "" : body;
    }

    public int status() { return status; }
    public String body() { return body; }

    public boolean ok() {
        return status >= 200 && status < 300;
    }
}
