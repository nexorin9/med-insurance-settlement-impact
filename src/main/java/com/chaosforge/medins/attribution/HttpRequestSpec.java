package com.chaosforge.medins.attribution;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * provider-neutral 的 HTTP 请求描述（POJO）。
 *
 * <p>网关只描述「方法 / URL / 头 / 体 / 超时秒数」，不绑定任何厂商 SDK。
 * {@link HttpExecutor} 负责把它变成真实传输，测试可注入协议替身读取本对象做协议形状断言。
 */
public final class HttpRequestSpec {

    private final String method;
    private final String url;
    private final Map<String, String> headers;
    private final String body;
    private final int timeoutSeconds;

    public HttpRequestSpec(String method,
                           String url,
                           Map<String, String> headers,
                           String body,
                           int timeoutSeconds) {
        this.method = method == null ? "" : method;
        this.url = url == null ? "" : url;
        this.headers = headers == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        this.body = body == null ? "" : body;
        this.timeoutSeconds = timeoutSeconds;
    }

    public String method() {
        return method;
    }

    public String url() {
        return url;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public String body() {
        return body;
    }

    public int timeoutSeconds() {
        return timeoutSeconds;
    }
}
