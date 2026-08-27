package group.aitools.nhs.platform.connector.service;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 表示{@code SdkMcpRemote}相关的领域对象。
 * MCP Java SDK adapter with no redirects, same-origin credentials and bounded pagination. */
@Component
public class SdkMcpRemoteClient implements McpRemoteClient {

    private static final int MAX_PAGES = 20;
    private final ConnectorEndpointPolicy endpointPolicy;

    public SdkMcpRemoteClient(ConnectorEndpointPolicy endpointPolicy) {
        this.endpointPolicy = endpointPolicy;
    }

    /**
     * 处理{@code discover}并返回对应结果。
     *
     * @param connection {@code connection}参数
     * @return 处理结果
     */
    @Override
    public DiscoveryResult discover(Connection connection) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        try (McpSyncClient client = client(connection)) {
            McpSchema.InitializeResult initialized = client.initialize();
            List<DiscoveredTool> tools = new ArrayList<>();
            Set<String> cursors = new HashSet<>();
            String cursor = null;
            for (int page = 0; page < MAX_PAGES; page++) {
                McpSchema.ListToolsResult result = cursor == null
                    ? client.listTools() : client.listTools(cursor);
                if (result == null || result.tools() == null) {
                    throw new McpRemoteException("MCP 服务返回了无效工具列表");
                }
                for (McpSchema.Tool tool : result.tools()) {
                    if (tools.size() >= ConnectorConfigurationValidator.MAX_TOOLS) {
                        throw new McpRemoteException("MCP 工具数量超过 500 个限制");
                    }
                    tools.add(new DiscoveredTool(
                        tool.name(), tool.title(), tool.description(),
                        nullSafeMap(tool.inputSchema()), nullSafeMap(tool.outputSchema()),
                        annotations(tool.annotations())
                    ));
                }
                cursor = result.nextCursor();
                if (cursor == null || cursor.isBlank()) {
                    break;
                }
                if (!cursors.add(cursor)) {
                    throw new McpRemoteException("MCP 工具分页游标发生循环");
                }
                if (page == MAX_PAGES - 1) {
                    throw new McpRemoteException("MCP 工具分页超过允许范围");
                }
            }
            return new DiscoveryResult(
                initialized.protocolVersion(), serverInfo(initialized.serverInfo()), List.copyOf(tools)
            );
        } catch (McpRemoteException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new McpRemoteException("MCP 服务连接或协议协商失败", exception);
        }
    }

    /**
     * 执行{@code invoke}相关的处理流程。
     *
     * @param connection {@code connection}参数
     * @param remoteToolName 名称
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    @Override
    public InvocationResult invoke(
        Connection connection,
        String remoteToolName,
        Map<String, Object> arguments
    ) {
        try (McpSession session = open(connection)) {
            return session.invoke(remoteToolName, arguments);
        } catch (McpRemoteException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new McpRemoteException("MCP 工具调用失败", exception);
        }
    }

    /**
     * 处理{@code open}并返回对应结果。
     *
     * @param connection {@code connection}参数
     * @return 处理结果
     */
    @Override
    public McpSession open(Connection connection) {
        McpSyncClient client = client(connection);
        try {
            client.initialize();
        } catch (RuntimeException exception) {
            closeAfterFailure(client, exception);
            throw new McpRemoteException("MCP 服务连接或协议协商失败", exception);
        }
        return new McpSession() {
            private final AtomicBoolean closed = new AtomicBoolean();

            /**
             * 执行{@code invoke}相关的处理流程。
             *
             * @param remoteToolName 名称
             * @param arguments {@code arguments}参数
             * @return 处理结果
             */
            @Override
            public synchronized InvocationResult invoke(
                String remoteToolName,
                Map<String, Object> arguments
            ) {
                // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
                if (closed.get()) {
                    throw new McpRemoteException("MCP 连接已经关闭");
                }
                try {
                    McpSchema.CallToolResult result = client.callTool(
                        new McpSchema.CallToolRequest(remoteToolName, arguments)
                    );
                    if (result == null) {
                        throw new McpRemoteException("MCP 工具没有返回结果");
                    }
                    return new InvocationResult(
                        Boolean.TRUE.equals(result.isError()), result.content(),
                        result.structuredContent(),
                        result.meta() == null ? Map.of() : Map.copyOf(result.meta())
                    );
                } catch (McpRemoteException exception) {
                    throw exception;
                } catch (RuntimeException exception) {
                    throw new McpRemoteException("MCP 工具调用失败", exception);
                }
            }

            /**
             * 处理{@code close}相关逻辑。
             */
            @Override
            public void close() {
                if (closed.compareAndSet(false, true)) {
                    client.close();
                }
            }
        };
    }

    /**
     * 处理{@code closeAfterFailure}相关逻辑。
     *
     * @param client 客户端参数
     * @param failure {@code failure}参数
     */
    private void closeAfterFailure(McpSyncClient client, RuntimeException failure) {
        try {
            client.close();
        } catch (RuntimeException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    /**
     * 处理客户端并返回对应结果。
     *
     * @param connection {@code connection}参数
     * @return 处理结果
     */
    private McpSyncClient client(Connection connection) {
        endpointPolicy.validateNetworkTarget(connection.endpoint());
        McpClientTransport transport = transport(connection);
        return McpClient.sync(transport)
            .requestTimeout(connection.requestTimeout())
            .initializationTimeout(connection.requestTimeout())
            .clientInfo(new McpSchema.Implementation("nhs", "1.0"))
            .build();
    }

    /**
     * 处理{@code transport}并返回对应结果。
     *
     * @param connection {@code connection}参数
     * @return 处理结果
     */
    private McpClientTransport transport(Connection connection) {
        URI endpoint = connection.endpoint();
        String origin = endpoint.getScheme() + "://" + endpoint.getRawAuthority();
        String path = endpoint.getRawPath();
        if (path == null || path.isBlank()) {
            path = "/";
        }
        HttpClient.Builder httpClient = HttpClient.newBuilder()
            .connectTimeout(connection.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1);
        HttpRequest.Builder request = HttpRequest.newBuilder()
            .timeout(connection.requestTimeout())
            .header("User-Agent", "nhs-mcp/1.0");
        if ("sse".equals(connection.transport())) {
            return HttpClientSseClientTransport.builder(origin)
                .sseEndpoint(path)
                .clientBuilder(httpClient)
                .requestBuilder(request)
                .httpRequestCustomizer((builder, method, uri, body, context) ->
                    customizeRequest(connection, builder, uri))
                .connectTimeout(connection.connectTimeout())
                .build();
        }
        return HttpClientStreamableHttpTransport.builder(origin)
            .endpoint(path)
            .clientBuilder(httpClient)
            .requestBuilder(request)
            .httpRequestCustomizer((builder, method, uri, body, context) ->
                customizeRequest(connection, builder, uri))
            .connectTimeout(connection.connectTimeout())
            .openConnectionOnStartup(false)
            .build();
    }

    /**
     * 处理{@code customizeRequest}相关逻辑。
     *
     * @param connection {@code connection}参数
     * @param builder {@code builder}参数
     * @param requested {@code requested}参数
     */
    private void customizeRequest(Connection connection, HttpRequest.Builder builder, URI requested) {
        endpointPolicy.requireSameOrigin(connection.endpoint(), requested);
        endpointPolicy.validateNetworkTarget(requested);
        if (connection.credential() == null) {
            return;
        }
        switch (connection.authType()) {
            case "bearer" -> builder.header("Authorization", "Bearer " + connection.credential());
            case "header" -> builder.header(connection.authHeader(), connection.credential());
            default -> throw new McpRemoteException("MCP 鉴权配置无效");
        }
    }

    /**
     * 处理{@code serverInfo}并返回对应结果。
     *
     * @param info {@code info}参数
     * @return 处理结果
     */
    private Map<String, Object> serverInfo(McpSchema.Implementation info) {
        if (info == null) {
            return Map.of();
        }
        Map<String, Object> value = new LinkedHashMap<>();
        put(value, "name", info.name());
        put(value, "title", info.title());
        put(value, "version", info.version());
        put(value, "description", info.description());
        put(value, "websiteUrl", info.websiteUrl());
        return Map.copyOf(value);
    }

    /**
     * 处理{@code put}相关逻辑。
     *
     * @param target {@code target}参数
     * @param key {@code key}参数
     * @param value {@code value}参数
     */
    private void put(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    /**
     * 处理{@code nullSafeMap}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Map<String, Object> nullSafeMap(Map<String, Object> value) {
        return value == null ? Map.of() : Map.copyOf(value);
    }

    /**
     * 处理{@code annotations}并返回对应结果。
     *
     * @param source 数据源参数
     * @return 处理结果
     */
    private Map<String, Object> annotations(McpSchema.ToolAnnotations source) {
        if (source == null) {
            return Map.of();
        }
        Map<String, Object> value = new LinkedHashMap<>();
        put(value, "title", source.title());
        put(value, "readOnly", source.readOnlyHint());
        put(value, "destructive", source.destructiveHint());
        put(value, "idempotent", source.idempotentHint());
        put(value, "openWorld", source.openWorldHint());
        put(value, "returnDirect", source.returnDirect());
        return Map.copyOf(value);
    }

    /**
     * 处理{@code put}相关逻辑。
     *
     * @param target {@code target}参数
     * @param key {@code key}参数
     * @param value {@code value}参数
     */
    private void put(Map<String, Object> target, String key, Boolean value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
