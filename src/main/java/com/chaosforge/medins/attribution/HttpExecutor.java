package com.chaosforge.medins.attribution;

import java.io.IOException;

/**
 * 可注入的 HTTP 传输接口 —— 业务层与 provider-neutral 网关之间唯一的传输边界。
 *
 * <p>默认实现基于 JDK {@code java.net.http.HttpClient}（见 {@code OpenAiCompatibleGateway} 内部私有实现），
 * 产品代码走真实 HTTP 协议；本地协议替身只在测试层注入，产品代码不依赖替身。
 *
 * <p>超时约定（实现与测试替身共同遵守，均为 JDK 标准异常，provider-neutral）：
 * <ul>
 *   <li>连接超时抛 {@code java.net.http.HttpConnectTimeoutException}（归类 {@link ModelErrorCode#MODEL_CONNECT_TIMEOUT}）；</li>
 *   <li>读/响应超时抛 {@code java.net.http.HttpTimeoutException}（归类 {@link ModelErrorCode#MODEL_READ_TIMEOUT}）；</li>
 *   <li>其余连接类 {@link IOException} 归入连接超时码；</li>
 *   <li>HTTP 4xx / 5xx 与空响应通过 {@link HttpResponse#status()} / {@link HttpResponse#body()} 返回，不抛异常。</li>
 * </ul>
 */
@FunctionalInterface
public interface HttpExecutor {

    HttpResponse execute(HttpRequestSpec spec) throws IOException;
}
