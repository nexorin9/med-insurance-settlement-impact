package com.chaosforge.medins.attribution;

/**
 * provider-neutral 的模型网关接口 —— 业务层只依赖本接口。
 *
 * <p>方法只表达业务语义（语义归因），不出现任何厂商 SDK 类型。院内自建端点、本地模型与云服务
 * 共用同一配置面，换一家 OpenAI-compatible 实现只需替换本接口的实现，不动 attribution 业务代码。
 */
public interface ModelGateway {

    /**
     * 对候选边做语义归因。
     *
     * @throws ModelGatewayException 六类归一错误之一（超时 / 4xx / 5xx / 空响应 / schema 不合规）
     */
    AttributionResult attribute(AttributionRequest request) throws ModelGatewayException;

    /** 网关实现名（用于审计与自检，不含凭据）。 */
    String gatewayName();
}
