package com.chaosforge.medins.adapter;

/** 台账幂等写入响应：状态码、响应体、幂等键，以及是否为「重复提交命中客户端缓存」。 */
public final class WriteResponse {

    private final int status;
    private final String body;
    private final String idempotencyKey;
    private final boolean duplicate;

    public WriteResponse(int status, String body, String idempotencyKey, boolean duplicate) {
        this.status = status;
        this.body = body == null ? "" : body;
        this.idempotencyKey = idempotencyKey == null ? "" : idempotencyKey;
        this.duplicate = duplicate;
    }

    public int status() { return status; }
    public String body() { return body; }
    public String idempotencyKey() { return idempotencyKey; }

    /** 是否 2xx。 */
    public boolean ok() { return status >= 200 && status < 300; }

    /** 重复提交命中客户端已提交键集合时为真（未产生第二次网络写入）。 */
    public boolean duplicate() { return duplicate; }

    public WriteResponse withDuplicate(boolean value) {
        return new WriteResponse(status, body, idempotencyKey, value);
    }
}
