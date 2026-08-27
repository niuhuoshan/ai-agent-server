package group.aitools.nhs.platform.connector.service;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 处理{@code discover}并返回对应结果。
 *
 * 定义{@code McpRemote}相关能力的服务契约。
 * Bounded transport abstraction for MCP discovery and invocation. */
public interface McpRemoteClient {

    DiscoveryResult discover(Connection connection);

    /**
     * 执行{@code invoke}相关的处理流程。
     *
     * @param connection {@code connection}参数
     * @param remoteToolName 名称
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    InvocationResult invoke(Connection connection, String remoteToolName, Map<String, Object> arguments);

    /**
 * 处理{@code open}并返回对应结果。
 *
     * Opens one initialized MCP session. Implementations that cannot retain a transport may
     * use the default short-lived adapter; the SDK implementation keeps the real client open.
     */
    default McpSession open(Connection connection) {
        return new McpSession() {
            /**
             * 执行{@code invoke}相关的处理流程。
             *
             * @param remoteToolName 名称
             * @param arguments {@code arguments}参数
             * @return 处理结果
             */
            @Override
            public InvocationResult invoke(String remoteToolName, Map<String, Object> arguments) {
                return McpRemoteClient.this.invoke(connection, remoteToolName, arguments);
            }

            /**
             * 处理{@code close}相关逻辑。
             */
            @Override
            public void close() {
                // The compatibility adapter owns no persistent transport.
            }
        };
    }

    /**
     * 定义Mcp会话相关能力的服务契约。
     */
    interface McpSession extends AutoCloseable {

        /**
         * 执行{@code invoke}相关的处理流程。
         *
         * @param remoteToolName 名称
         * @param arguments {@code arguments}参数
         * @return 处理结果
         */
        InvocationResult invoke(String remoteToolName, Map<String, Object> arguments);

        /**
         * 处理{@code close}相关逻辑。
         */
        @Override
        void close();
    }

    /**
     * 封装{@code Connection}相关的不可变数据。
     */
    record Connection(
        URI endpoint,
        String transport,
        String authType,
        String authHeader,
        String credential,
        Duration connectTimeout,
        Duration requestTimeout
    ) {
    }

    /**
     * 封装Discovered工具相关的不可变数据。
     */
    record DiscoveredTool(
        String name,
        String title,
        String description,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        Map<String, Object> annotations
    ) {
    }

    /**
     * 封装{@code Discovery}相关的不可变数据。
     */
    record DiscoveryResult(
        String protocolVersion,
        Map<String, Object> serverInfo,
        List<DiscoveredTool> tools
    ) {
    }

    /**
     * 封装调用相关的不可变数据。
     */
    record InvocationResult(
        boolean error,
        Object content,
        Object structuredContent,
        Map<String, Object> metadata
    ) {
    }
}
