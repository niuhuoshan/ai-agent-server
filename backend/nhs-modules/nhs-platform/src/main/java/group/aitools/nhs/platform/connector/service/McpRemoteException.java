package group.aitools.nhs.platform.connector.service;

/**
 * 表示{@code McpRemote}处理过程中发生的业务异常。
 * Sanitized MCP transport or protocol failure. */
public class McpRemoteException extends RuntimeException {

    public McpRemoteException(String message) {
        super(message);
    }

    /**
     * 创建 {@code McpRemoteException} 实例并初始化所需依赖。
     *
     * @param message 待处理内容
     * @param cause {@code cause}参数
     */
    public McpRemoteException(String message, Throwable cause) {
        super(message, cause);
    }
}
