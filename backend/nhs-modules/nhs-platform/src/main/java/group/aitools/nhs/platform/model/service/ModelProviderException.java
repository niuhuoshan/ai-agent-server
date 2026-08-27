package group.aitools.nhs.platform.model.service;

/**
 * 表示模型提供方处理过程中发生的业务异常。
 * Safe provider failure whose message is suitable for an administrator response. */
public class ModelProviderException extends RuntimeException {

    public ModelProviderException(String message) {
        super(message);
    }
}
