package group.aitools.nhs.platform.connector.service;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.connector.domain.AgentConnector;
import group.aitools.nhs.platform.connector.domain.AgentMcpDiscovery;
import group.aitools.nhs.platform.connector.domain.AgentTool;
import group.aitools.nhs.platform.connector.mapper.ConnectorCatalogMapper;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 负责{@code McpDiscoveryPersistence}相关的业务编排与领域规则处理。
 * Keeps each MCP discovery result and its tool catalog transition atomic. */
@Service
public class McpDiscoveryPersistenceService {

    private final ConnectorCatalogMapper mapper;
    private final PlatformIdGenerator idGenerator;

    public McpDiscoveryPersistenceService(
        ConnectorCatalogMapper mapper,
        PlatformIdGenerator idGenerator
    ) {
        this.mapper = mapper;
        this.idGenerator = idGenerator;
    }

    /**
     * 处理{@code begin}并返回对应结果。
     *
     * @param connectorId 资源标识
     * @param actorId 资源标识
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public DiscoveryWork begin(Long connectorId, Long actorId) {
        mapper.lockConnector(connectorId);
        AgentConnector connector = requireMcpConnector(connectorId);
        if ("disabled".equals(connector.getStatus())) {
            throw conflict("已停用 MCP 连接器不能发现工具");
        }
        LocalDateTime now = LocalDateTime.now();
        mapper.failStaleDiscoveries(connectorId, now.minusHours(1), now);
        AgentMcpDiscovery discovery = new AgentMcpDiscovery();
        discovery.setId(idGenerator.nextId());
        discovery.setConnectorId(connectorId);
        discovery.setConnectorRevision(connector.getRevisionNo());
        discovery.setStatus("running");
        discovery.setToolCount(0);
        discovery.setStartedBy(actorId);
        discovery.setStartedAt(now);
        try {
            mapper.insertDiscovery(discovery);
        } catch (DuplicateKeyException exception) {
            throw conflict("该 MCP 连接器已有进行中的发现任务");
        }
        return new DiscoveryWork(connector, discovery);
    }

    /**
     * 处理{@code complete}并返回对应结果。
     *
     * @param work {@code work}参数
     * @param result 结果参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean complete(DiscoveryWork work, PreparedDiscovery result) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        LocalDateTime now = LocalDateTime.now();
        mapper.lockConnector(work.connector().getId());
        AgentConnector current = mapper.selectConnectorById(work.connector().getId());
        if (current == null || !work.connector().getRevisionNo().equals(current.getRevisionNo())) {
            AgentMcpDiscovery failed = failure(
                work.discovery(), "连接器配置在发现期间发生变化，结果已丢弃", now
            );
            mapper.failDiscovery(failed);
            return false;
        }

        Set<String> remoteNames = new HashSet<>();
        for (PreparedRemoteTool remote : result.tools()) {
            remoteNames.add(remote.externalName());
            AgentTool existing = mapper.selectLatestRemoteTool(
                current.getId(), remote.externalName()
            );
            if (existing != null
                && remote.schemaHash().equals(existing.getRemoteSchemaHash())
                && !"deprecated".equals(existing.getStatus())
                && Boolean.TRUE.equals(existing.getIsAvailable())) {
                mapper.markRemoteToolSeen(existing.getId(), work.discovery().getId(), work.discovery().getStartedBy(), now);
                continue;
            }
            if (existing != null
                && remote.schemaHash().equals(existing.getRemoteSchemaHash())
                && mapper.recoverRemoteTool(
                    existing.getId(), current.getRevisionNo(), remote.schemaHash(),
                    work.discovery().getId(), work.discovery().getStartedBy(), now
                ) == 1) {
                continue;
            }
            if (existing != null) {
                mapper.deprecateRemoteTool(existing.getId(), work.discovery().getStartedBy(), now);
            }
            AgentTool tool = tool(current, work.discovery(), remote, existing, now);
            mapper.insertTool(tool);
        }

        for (AgentTool existing : mapper.selectLatestConnectorTools(current.getId())) {
            if (existing.getExternalName() != null && !remoteNames.contains(existing.getExternalName())
                && Boolean.TRUE.equals(existing.getIsAvailable())) {
                mapper.deprecateRemoteTool(existing.getId(), work.discovery().getStartedBy(), now);
            }
        }

        AgentMcpDiscovery completed = work.discovery();
        completed.setStatus("succeeded");
        completed.setProtocolVersion(result.protocolVersion());
        completed.setServerInfoJson(result.serverInfoJson());
        completed.setToolCount(result.tools().size());
        completed.setContentHash(result.contentHash());
        completed.setCompletedAt(now);
        if (mapper.completeDiscovery(completed) != 1
            || mapper.markDiscoverySucceeded(
                current.getId(), current.getRevisionNo(), completed.getId(), now
            ) != 1) {
            throw conflict("MCP 发现结果发生并发冲突");
        }
        return true;
    }

    /**
     * 处理{@code fail}相关逻辑。
     *
     * @param work {@code work}参数
     * @param errorSummary {@code errorSummary}参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void fail(DiscoveryWork work, String errorSummary) {
        LocalDateTime now = LocalDateTime.now();
        AgentMcpDiscovery failed = failure(work.discovery(), errorSummary, now);
        mapper.failDiscovery(failed);
        mapper.markDiscoveryFailed(
            work.connector().getId(), work.connector().getRevisionNo(), errorSummary, now
        );
    }

    /**
     * 将输入数据转换为{@code ol}。
     *
     * @param connector 连接器参数
     * @param discovery {@code discovery}参数
     * @param remote {@code remote}参数
     * @param existing {@code existing}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    private AgentTool tool(
        AgentConnector connector,
        AgentMcpDiscovery discovery,
        PreparedRemoteTool remote,
        AgentTool existing,
        LocalDateTime now
    ) {
        AgentTool tool = new AgentTool();
        tool.setId(idGenerator.nextId());
        tool.setToolKey(existing == null ? remote.toolKey() : existing.getToolKey());
        tool.setName(remote.name());
        tool.setDescription(remote.description());
        tool.setConnectorId(connector.getId());
        tool.setToolType("mcp");
        tool.setRiskLevel(remote.riskLevel());
        tool.setParameterSchemaJson(remote.parameterSchemaJson());
        tool.setExecutionPolicyJson(remote.executionPolicyJson());
        tool.setExternalName(remote.externalName());
        tool.setStatus(existing != null && "active".equals(existing.getStatus())
            ? "active" : "disabled");
        tool.setVersionNo(existing == null ? 1 : mapper.selectNextToolVersion(existing.getToolKey()));
        tool.setDiscoveryId(discovery.getId());
        tool.setRemoteSchemaHash(remote.schemaHash());
        tool.setIsAvailable(true);
        tool.setCreateBy(discovery.getStartedBy());
        tool.setCreateTime(now);
        tool.setDelFlag("0");
        tool.setExtraJson("{}");
        return tool;
    }

    /**
     * 处理{@code failure}并返回对应结果。
     *
     * @param discovery {@code discovery}参数
     * @param errorSummary {@code errorSummary}参数
     * @param now {@code now}参数
     * @return 处理结果
     */
    private AgentMcpDiscovery failure(
        AgentMcpDiscovery discovery,
        String errorSummary,
        LocalDateTime now
    ) {
        discovery.setStatus("failed");
        discovery.setErrorSummary(errorSummary);
        discovery.setCompletedAt(now);
        return discovery;
    }

    /**
     * 校验Mcp连接器，并在条件不满足时终止处理。
     *
     * @param connectorId 资源标识
     * @return 处理结果
     */
    private AgentConnector requireMcpConnector(Long connectorId) {
        AgentConnector connector = mapper.selectConnectorById(connectorId);
        if (connector == null) {
            throw new ServiceException("连接器不存在", HttpStatus.NOT_FOUND);
        }
        if (!"mcp".equals(connector.getProviderType())) {
            throw new ServiceException("只有 MCP 连接器可以发现工具", HttpStatus.BAD_REQUEST);
        }
        return connector;
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

    /**
     * 封装{@code DiscoveryWork}相关的不可变数据。
     */
    public record DiscoveryWork(AgentConnector connector, AgentMcpDiscovery discovery) {
    }

    /**
     * 封装{@code PreparedDiscovery}相关的不可变数据。
     */
    public record PreparedDiscovery(
        String protocolVersion,
        String serverInfoJson,
        String contentHash,
        List<PreparedRemoteTool> tools
    ) {
    }

    /**
     * 封装PreparedRemote工具相关的不可变数据。
     */
    public record PreparedRemoteTool(
        String toolKey,
        String externalName,
        String name,
        String description,
        String riskLevel,
        String parameterSchemaJson,
        String executionPolicyJson,
        String schemaHash
    ) {
    }
}
