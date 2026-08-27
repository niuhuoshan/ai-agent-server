package group.aitools.nhs.platform.workflow.service;

import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.workflow.persistence.row.WorkflowTemplateRow;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 表示工作流Graph相关的领域对象。
 * Strictly accepts the two published first-phase graphs; arbitrary DAGs fail closed. */
@Component
public class WorkflowGraphValidator {

    private static final int MAX_DOCUMENT_BYTES = 64 * 1024;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final Set<String> GRAPH_KEYS = Set.of(
        "schemaVersion", "templateKey", "maxParallelism", "roles", "nodes"
    );
    private static final Set<String> ROLE_KEYS = Set.of("key", "name");
    private static final Set<String> AGENT_NODE_KEYS = Set.of(
        "key", "type", "role", "sequence", "dependsOn", "instruction"
    );
    private static final Set<String> AGGREGATE_NODE_KEYS = Set.of(
        "key", "type", "sequence", "dependsOn", "instruction"
    );

    private final JsonMapper jsonMapper;

    /**
     * 创建 {@code WorkflowGraphValidator} 实例并初始化所需依赖。
     *
     * @param jsonMapper {@code jsonMapper}参数
     */
    public WorkflowGraphValidator(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    /**
     * 校验{@code validate}，并在条件不满足时终止处理。
     *
     * @param row {@code row}参数
     * @return 处理结果
     */
    public WorkflowTemplate validate(WorkflowTemplateRow row) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (row == null || row.getPublishedAt() == null
            || !"active".equals(row.getWorkflowStatus())
            || !"published".equals(row.getVersionStatus())
            || !"fixed_template".equals(row.getWorkflowType())) {
            throw conflict("工作流模板未发布或不可用");
        }
        Map<String, Object> graph = document(row.getGraphJson(), "工作流图");
        Map<String, Object> policy = document(row.getRuntimePolicyJson(), "工作流运行策略");
        requireExactKeys(graph, GRAPH_KEYS, "工作流图");
        if (integer(graph.get("schemaVersion"), "schemaVersion") != 1) {
            throw conflict("工作流图版本不受支持");
        }
        String templateKey = text(graph.get("templateKey"), "templateKey", 64);
        if (!templateKey.equals(row.getWorkflowKey())) {
            throw conflict("工作流图模板标识不一致");
        }
        String canonical = jsonMapper.writeValueAsString(canonicalize(graph));
        if (!ContentHashing.sha256(canonical).equals(row.getContentHash())) {
            throw conflict("工作流版本内容哈希不一致");
        }

        int maxParallelism = integer(graph.get("maxParallelism"), "maxParallelism");
        if (maxParallelism < 1 || maxParallelism > 3) {
            throw conflict("工作流并行度超出一期限制");
        }
        int policyParallelism = integer(policy.get("maxParallelism"), "策略maxParallelism");
        int dependencyBytes = integer(policy.get("maxDependencyBytes"), "maxDependencyBytes");
        if (policyParallelism != maxParallelism || dependencyBytes < 1024
            || dependencyBytes > 65536 || !Boolean.TRUE.equals(policy.get("failFast"))) {
            throw conflict("工作流运行策略无效");
        }

        List<WorkflowTemplate.WorkflowRole> roles = roles(graph.get("roles"));
        List<WorkflowNode> nodes = nodes(graph.get("nodes"), roles);
        validateDependencies(nodes);
        validateFixedShape(templateKey, roles, nodes, maxParallelism);
        return new WorkflowTemplate(
            row.getWorkflowId(), row.getVersionId(), row.getVersionNo(), templateKey,
            row.getName(), row.getContentHash(), maxParallelism, dependencyBytes,
            roles, nodes, Map.copyOf(policy)
        );
    }

    /**
     * 处理{@code roles}并返回对应结果。
     *
     * @param raw {@code raw}参数
     * @return 符合条件的数据集合
     */
    private List<WorkflowTemplate.WorkflowRole> roles(Object raw) {
        if (!(raw instanceof List<?> values) || values.size() < 2 || values.size() > 3) {
            throw conflict("工作流角色数量无效");
        }
        List<WorkflowTemplate.WorkflowRole> result = new ArrayList<>(values.size());
        Set<String> keys = new HashSet<>();
        for (Object value : values) {
            Map<String, Object> role = object(value, "工作流角色");
            requireExactKeys(role, ROLE_KEYS, "工作流角色");
            String key = identifier(role.get("key"), "角色标识");
            if (!keys.add(key)) {
                throw conflict("工作流角色重复");
            }
            result.add(new WorkflowTemplate.WorkflowRole(
                key, text(role.get("name"), "角色名称", 128)
            ));
        }
        return List.copyOf(result);
    }

    /**
     * 处理{@code nodes}并返回对应结果。
     *
     * @param raw {@code raw}参数
     * @param roles {@code roles}参数
     * @return 符合条件的数据集合
     */
    private List<WorkflowNode> nodes(
        Object raw,
        List<WorkflowTemplate.WorkflowRole> roles
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (!(raw instanceof List<?> values) || values.size() < 2 || values.size() > 8) {
            throw conflict("工作流节点数量无效");
        }
        Set<String> roleKeys = roles.stream().map(WorkflowTemplate.WorkflowRole::key)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<WorkflowNode> result = new ArrayList<>(values.size());
        Set<String> keys = new HashSet<>();
        Set<Integer> sequences = new HashSet<>();
        for (Object value : values) {
            Map<String, Object> node = object(value, "工作流节点");
            String type = text(node.get("type"), "节点类型", 24);
            requireExactKeys(
            node, "agent".equals(type) ? AGENT_NODE_KEYS : AGGREGATE_NODE_KEYS,
                "工作流节点"
            );
            if (!Set.of("agent", "aggregate").contains(type)) {
                throw conflict("一期工作流包含不支持的节点类型");
            }
            String key = identifier(node.get("key"), "节点标识");
            int sequence = integer(node.get("sequence"), "节点顺序");
            if (!keys.add(key) || !sequences.add(sequence)) {
                throw conflict("工作流节点标识或顺序重复");
            }
            String role = "agent".equals(type)
                ? identifier(node.get("role"), "节点角色") : null;
            if (role != null && !roleKeys.contains(role)) {
                throw conflict("工作流节点引用未知角色");
            }
            result.add(new WorkflowNode(
                key, type, role, sequence, identifiers(node.get("dependsOn"), "节点依赖"),
                text(node.get("instruction"), "节点指令", 2000)
            ));
        }
        result.sort(java.util.Comparator.comparingInt(WorkflowNode::sequence));
        for (int index = 0; index < result.size(); index++) {
            if (result.get(index).sequence() != index + 1) {
                throw conflict("工作流节点顺序必须连续");
            }
        }
        return List.copyOf(result);
    }

    /**
     * 校验{@code Dependencies}，并在条件不满足时终止处理。
     *
     * @param nodes {@code nodes}参数
     */
    private void validateDependencies(List<WorkflowNode> nodes) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        Map<String, Integer> sequences = new LinkedHashMap<>();
        nodes.forEach(node -> sequences.put(node.key(), node.sequence()));
        for (WorkflowNode node : nodes) {
            Set<String> unique = new LinkedHashSet<>(node.dependsOn());
            if (unique.size() != node.dependsOn().size()) {
                throw conflict("工作流节点依赖重复");
            }
            for (String dependency : unique) {
                Integer sequence = sequences.get(dependency);
                if (sequence == null || sequence >= node.sequence()) {
                    throw conflict("工作流节点依赖无效或形成环");
                }
            }
        }
    }

    /**
     * 校验{@code FixedShape}，并在条件不满足时终止处理。
     *
     * @param key {@code key}参数
     * @param roles {@code roles}参数
     * @param nodes {@code nodes}参数
     * @param parallelism {@code parallelism}参数
     */
    private void validateFixedShape(
        String key,
        List<WorkflowTemplate.WorkflowRole> roles,
        List<WorkflowNode> nodes,
        int parallelism
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        List<String> roleKeys = roles.stream().map(WorkflowTemplate.WorkflowRole::key).toList();
        Map<String, String> signatures = new LinkedHashMap<>();
        for (WorkflowNode node : nodes) {
            signatures.put(node.key(), node.type() + ':' + String.valueOf(node.role())
                + ':' + String.join(",", node.dependsOn()));
        }
        if ("supervisor_executor".equals(key)) {
            if (parallelism != 1 || !roleKeys.equals(List.of("supervisor", "executor"))
                || !signatures.equals(Map.of(
                    "supervisor_plan", "agent:supervisor:",
                    "executor", "agent:executor:supervisor_plan",
                    "supervisor_review", "agent:supervisor:executor"
                ))) {
                throw conflict("主管执行者模板结构被篡改");
            }
            return;
        }
        if ("delivery_team".equals(key)) {
            if (parallelism != 3 || !roleKeys.equals(List.of("backend", "frontend", "test"))
                || !signatures.equals(Map.of(
                    "backend", "agent:backend:",
                    "frontend", "agent:frontend:",
                    "test", "agent:test:",
                    "summary", "aggregate:null:backend,frontend,test"
                ))) {
                throw conflict("交付团队模板结构被篡改");
            }
            return;
        }
        throw conflict("一期只允许内置固定工作流模板");
    }

    /**
     * 处理文档并返回对应结果。
     *
     * @param json {@code json}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private Map<String, Object> document(String json, String label) {
        if (json == null || json.isBlank()
            || json.getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES) {
            throw conflict(label + "为空或超过64KB限制");
        }
        try {
            Map<String, Object> value = jsonMapper.readValue(json, MAP_TYPE);
            return value == null ? Map.of() : value;
        } catch (RuntimeException exception) {
            throw conflict(label + "格式无效");
        }
    }

    /**
     * 处理{@code object}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private Map<String, Object> object(Object value, String label) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw conflict(label + "格式无效");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    /**
     * 处理{@code identifiers}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 符合条件的数据集合
     */
    private List<String> identifiers(Object value, String label) {
        if (!(value instanceof List<?> list) || list.size() > 8) {
            throw conflict(label + "格式无效");
        }
        return list.stream().map(item -> identifier(item, label)).toList();
    }

    /**
     * 处理{@code identifier}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String identifier(Object value, String label) {
        String normalized = text(value, label, 64);
        if (!normalized.matches("[a-z][a-z0-9_]{0,63}")) {
            throw conflict(label + "无效");
        }
        return normalized;
    }

    /**
     * 处理{@code text}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @param maximum {@code maximum}参数
     * @return 处理结果
     */
    private String text(Object value, String label, int maximum) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > maximum
            || text.indexOf('\0') >= 0) {
            throw conflict(label + "无效");
        }
        return text.strip();
    }

    /**
     * 处理{@code integer}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private int integer(Object value, String label) {
        if (!(value instanceof Number number) || number.doubleValue() != number.intValue()) {
            throw conflict(label + "必须是整数");
        }
        return number.intValue();
    }

    /**
     * 校验{@code ExactKeys}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param allowed {@code allowed}参数
     * @param label {@code label}参数
     */
    private void requireExactKeys(Map<String, Object> value, Set<String> allowed, String label) {
        if (!value.keySet().equals(allowed)) {
            throw conflict(label + "包含缺失或不支持的字段");
        }
    }

    /**
     * 判断{@code onicalize}是否满足要求。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), canonicalize(item)));
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::canonicalize).toList();
        }
        return value;
    }

    /**
     * 处理{@code conflict}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException conflict(String message) {
        return new ServiceException(message, HttpStatus.CONFLICT);
    }
}
