package com.chaosforge.medins.adapter;

import java.io.IOException;
import java.util.Map;

/**
 * HTTP 传输抽象：默认实现用 JDK {@code HttpClient}（见 {@link IfaceRegistryClient} 内部类），
 * 本地协议替身只在测试层注入。超时以 {@code timeoutSeconds} 按次传入，便于可配。
 */
@FunctionalInterface
public interface HttpTransport {

    /**
     * 执行一次 HTTP 调用。
     *
     * @param method         GET / POST
     * @param url            完整 URL
     * @param headers        请求头（含 Authorization、Idempotency-Key 等）
     * @param body           请求体；GET 传 null
     * @param timeoutSeconds 本次读超时秒数
     */
    RawResponse execute(String method, String url, Map<String, String> headers,
                        String body, int timeoutSeconds) throws IOException;
}
