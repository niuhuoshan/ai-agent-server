package group.aitools.nhs.platform.search.service;

/**
 * 表示Search提供方处理过程中发生的业务异常。
 * Stable, credential-free error code for search transport and policy failures. */
public final class SearchProviderException extends RuntimeException {

    private final String errorCode;

    public SearchProviderException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 处理{@code errorCode}并返回对应结果。
     *
     * @return 处理结果
     */
    public String errorCode() {
        return errorCode;
    }
}
