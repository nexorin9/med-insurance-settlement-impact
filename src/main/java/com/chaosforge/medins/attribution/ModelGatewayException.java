package com.chaosforge.medins.attribution;

/**
 * 模型网关异常 —— 携带归一错误码，供六类失败分类、审计与降级路径消费。
 *
 * <p>异常消息在构造前须经 {@code SecretRedactor} 脱敏，绝不出现 API Key 等凭据字面量。
 */
public final class ModelGatewayException extends Exception {

    private static final long serialVersionUID = 1L;

    private final ModelErrorCode code;

    public ModelGatewayException(ModelErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ModelGatewayException(ModelErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** 归一错误码。 */
    public ModelErrorCode code() {
        return code;
    }
}
