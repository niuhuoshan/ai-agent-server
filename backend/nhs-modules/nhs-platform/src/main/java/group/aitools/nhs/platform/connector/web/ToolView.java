package group.aitools.nhs.platform.connector.web;

import group.aitools.nhs.platform.connector.domain.AgentTool;
import group.aitools.nhs.platform.connector.service.BuiltinToolCatalog;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 封装工具相关的不可变数据。
 */
public record ToolView(
    Long id,
    String toolKey,
    String name,
    String description,
    Long connectorId,
    String toolType,
    String riskLevel,
    Map<String, Object> parameterSchema,
    Map<String, Object> executionPolicy,
    String externalName,
    String status,
    Integer versionNo,
    Long discoveryId,
    String remoteSchemaHash,
    boolean available,
    int usageCount,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    String runtimeExecution
) {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /**
     * 处理{@code from}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param jsonMapper {@code jsonMapper}参数
     * @return 处理结果
     */
    public static ToolView from(AgentTool value, JsonMapper jsonMapper) {
        Map<String, Object> policy = map(value.getExecutionPolicyJson(), jsonMapper);
        String runtimeExecution = "configured";
        boolean runtimeAvailable = Boolean.TRUE.equals(value.getIsAvailable());
        if ("builtin".equals(value.getToolType())) {
            Object configuredHandler = policy.get("handlerKey");
            String handler = configuredHandler instanceof String text && !text.isBlank()
                ? text : value.getExternalName() == null ? value.getToolKey() : value.getExternalName();
            runtimeExecution = BuiltinToolCatalog.implemented(handler) ? "local" : "unavailable";
            runtimeAvailable = runtimeAvailable && "local".equals(runtimeExecution);
        }
        return new ToolView(
            value.getId(), value.getToolKey(), value.getName(), value.getDescription(),
            value.getConnectorId(), value.getToolType(), value.getRiskLevel(),
            map(value.getParameterSchemaJson(), jsonMapper),
            policy, value.getExternalName(),
            value.getStatus(), value.getVersionNo(), value.getDiscoveryId(),
            value.getRemoteSchemaHash(), runtimeAvailable,
            value.getUsageCount() == null ? 0 : value.getUsageCount(),
            value.getCreateTime(), value.getUpdateTime(), runtimeExecution
        );
    }

    /**
     * 将输入数据转换为{@code map}。
     *
     * @param value {@code value}参数
     * @param jsonMapper {@code jsonMapper}参数
     * @return 处理结果
     */
    private static Map<String, Object> map(String value, JsonMapper jsonMapper) {
        return value == null ? Map.of() : jsonMapper.readValue(value, MAP_TYPE);
    }
}
