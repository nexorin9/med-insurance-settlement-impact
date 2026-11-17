package com.chaosforge.medins.attribution;

import com.chaosforge.medins.config.SecretRedactor;

/**
 * 一次模型调用的审计条目（只增不改）。
 *
 * <p>字段：模型名、请求摘要、耗时、重试次数、错误码、是否降级、视图、批次、时间。
 * 文本字段（请求摘要）在构造时经 {@link SecretRedactor} 模式式脱敏，绝不出现 API Key 等凭据字面量，
 * 也不落患者可识别明细。错误码成功时为 {@code null}。
 */
public final class AuditEntry {

    private final String model;
    private final String requestDigest;
    private final long elapsedMs;
    private final int retries;
    private final ModelErrorCode errorCode;
    private final boolean degraded;
    private final String view;
    private final String batch;
    private final String at;

    public AuditEntry(String model,
                      String requestDigest,
                      long elapsedMs,
                      int retries,
                      ModelErrorCode errorCode,
                      boolean degraded,
                      String view,
                      String batch,
                      String at) {
        this.model = model == null ? "" : model;
        // 模式式脱敏：即便上游误把 Bearer / api_key= 拼进摘要，也会在此被抹掉。
        this.requestDigest = SecretRedactor.empty().redact(requestDigest == null ? "" : requestDigest);
        this.elapsedMs = elapsedMs;
        this.retries = retries;
        this.errorCode = errorCode;
        this.degraded = degraded;
        this.view = view == null ? "" : view;
        this.batch = batch == null ? "" : batch;
        this.at = at == null ? "" : at;
    }

    public String model() {
        return model;
    }

    public String requestDigest() {
        return requestDigest;
    }

    public long elapsedMs() {
        return elapsedMs;
    }

    public int retries() {
        return retries;
    }

    public ModelErrorCode errorCode() {
        return errorCode;
    }

    public boolean degraded() {
        return degraded;
    }

    public String view() {
        return view;
    }

    public String batch() {
        return batch;
    }

    public String at() {
        return at;
    }
}
