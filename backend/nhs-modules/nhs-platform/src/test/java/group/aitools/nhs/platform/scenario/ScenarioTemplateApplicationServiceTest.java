package group.aitools.nhs.platform.scenario.service;

import group.aitools.nhs.platform.agent.service.AgentApplicationService;
import group.aitools.nhs.platform.agent.web.AgentView;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.connector.service.ToolCatalogService;
import group.aitools.nhs.platform.data.service.DataSourceCatalogService;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.knowledge.service.KnowledgeApplicationService;
import group.aitools.nhs.platform.model.service.ModelApplicationService;
import group.aitools.nhs.platform.scenario.domain.AgentScenarioInstance;
import group.aitools.nhs.platform.scenario.domain.AgentScenarioUninstallRun;
import group.aitools.nhs.platform.scenario.mapper.ScenarioTemplateMapper;
import group.aitools.nhs.platform.scenario.web.ScenarioTemplateUninstallRequest;
import group.aitools.nhs.common.core.exception.ServiceException;
import group.aitools.nhs.common.core.constant.HttpStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class ScenarioTemplateApplicationServiceTest {

    private final CurrentPrincipalProvider principalProvider = mock(CurrentPrincipalProvider.class);
    private final PlatformIdGenerator idGenerator = mock(PlatformIdGenerator.class);
    private final ScenarioTemplateMapper mapper = mock(ScenarioTemplateMapper.class);
    private final AgentApplicationService agentService = mock(AgentApplicationService.class);
    private final ScenarioTemplateAuditService auditService = mock(ScenarioTemplateAuditService.class);
    private final ScenarioTemplateApplicationService service = new ScenarioTemplateApplicationService(
        principalProvider,
        idGenerator,
        mapper,
        agentService,
        mock(ModelApplicationService.class),
        mock(DataSourceCatalogService.class),
        mock(KnowledgeApplicationService.class),
        mock(ToolCatalogService.class),
        JsonMapper.builder().build(),
        auditService
    );

    @Test
    void uninstallDisablesTheGeneratedAgentAndPersistsAnIdempotentRun() {
        CurrentPrincipal principal = member(7L);
        AgentScenarioInstance instance = instance(101L, 7L, "installed");
        when(principalProvider.currentPrincipal()).thenReturn(principal);
        when(mapper.lockInstanceById(101L)).thenReturn(instance);
        when(agentService.get(301L)).thenReturn(agent(301L, "active", "knowledge-qa-assistant"));
        when(mapper.updateInstanceStatus(any(), any(), any())).thenReturn(1);
        when(idGenerator.nextId()).thenReturn(901L);

        var result = service.uninstall(101L, new ScenarioTemplateUninstallRequest(true, "业务已下线", "uninstall-101"));

        assertEquals("succeeded", result.status());
        assertEquals("disabled", result.agentStatus());
        assertEquals("installed", result.previousStatus());
        assertFalse(result.idempotent());
        verify(agentService).updateStatus(301L, "disabled");
        verify(mapper).updateInstanceStatus(any(), any(), any());
        ArgumentCaptor<AgentScenarioUninstallRun> run = ArgumentCaptor.forClass(AgentScenarioUninstallRun.class);
        verify(mapper).insertUninstallRun(run.capture());
        assertEquals("uninstall-101", run.getValue().getIdempotencyKey());
        assertEquals(7L, run.getValue().getCreatedBy());
        verify(auditService).record(principal, "scenario.uninstall", 101L, "success", "业务已下线", "template=knowledge-qa-assistant, agent_status=disabled");
    }

    @Test
    void successfulPriorRunMakesRetrySideEffectFree() {
        CurrentPrincipal principal = member(7L);
        AgentScenarioInstance instance = instance(101L, 7L, "disabled");
        AgentScenarioUninstallRun prior = new AgentScenarioUninstallRun();
        prior.setId(902L);
        prior.setInstanceId(101L);
        prior.setTemplateKey("knowledge-qa-assistant");
        prior.setIdempotencyKey("same-key");
        prior.setStatus("succeeded");
        prior.setPreviousStatus("installed");
        prior.setAgentStatus("disabled");
        prior.setReason("业务已下线");
        when(principalProvider.currentPrincipal()).thenReturn(principal);
        when(mapper.lockInstanceById(101L)).thenReturn(instance);
        when(mapper.selectUninstallRunByIdempotency(101L, "same-key")).thenReturn(prior);

        var result = service.uninstall(101L, new ScenarioTemplateUninstallRequest(true, "业务已下线", "same-key"));

        assertTrue(result.idempotent());
        verify(agentService, never()).get(any());
        verify(mapper, never()).insertUninstallRun(any());
        verify(mapper, never()).updateInstanceStatus(any(), any(), any());
    }

    @Test
    void missingGeneratedAgentStillLeavesAnAuditedDisabledInstance() {
        CurrentPrincipal principal = member(7L);
        AgentScenarioInstance instance = instance(101L, 7L, "installed");
        when(principalProvider.currentPrincipal()).thenReturn(principal);
        when(mapper.lockInstanceById(101L)).thenReturn(instance);
        when(agentService.get(301L)).thenThrow(new ServiceException("Agent 不存在", HttpStatus.NOT_FOUND));
        when(mapper.updateInstanceStatus(any(), any(), any())).thenReturn(1);
        when(idGenerator.nextId()).thenReturn(903L);

        var result = service.uninstall(101L, new ScenarioTemplateUninstallRequest(true, "清理遗留实例", "missing-agent"));

        assertEquals("not_found", result.agentStatus());
        assertTrue(result.warning().contains("关联 Agent 已不存在"));
        verify(agentService, never()).updateStatus(any(), any());
        verify(mapper).insertUninstallRun(any());
    }

    @Test
    void failedPriorRunRequiresANewIdempotencyKey() {
        CurrentPrincipal principal = member(7L);
        AgentScenarioUninstallRun prior = new AgentScenarioUninstallRun();
        prior.setStatus("failed");
        when(principalProvider.currentPrincipal()).thenReturn(principal);
        when(mapper.lockInstanceById(101L)).thenReturn(instance(101L, 7L, "installed"));
        when(mapper.selectUninstallRunByIdempotency(101L, "failed-key")).thenReturn(prior);

        assertThrows(
            ServiceException.class,
            () -> service.uninstall(101L, new ScenarioTemplateUninstallRequest(true, "重试", "failed-key"))
        );

        verify(agentService, never()).get(any());
        verify(mapper, never()).insertUninstallRun(any());
    }

    @Test
    void nonOwnerCannotUninstallAnotherUsersInstance() {
        CurrentPrincipal principal = member(7L);
        when(principalProvider.currentPrincipal()).thenReturn(principal);
        when(mapper.lockInstanceById(101L)).thenReturn(instance(101L, 8L, "installed"));

        assertThrows(
            ServiceException.class,
            () -> service.uninstall(101L, new ScenarioTemplateUninstallRequest(true, "无", "denied"))
        );

        verify(agentService, never()).get(any());
        verify(auditService).record(principal, "scenario.uninstall", 101L, "deny", "无权卸载该场景实例", "owner=8");
    }

    private CurrentPrincipal member(Long id) {
        return new CurrentPrincipal(id, "user-" + id, PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER));
    }

    private AgentScenarioInstance instance(Long id, Long ownerId, String status) {
        AgentScenarioInstance instance = new AgentScenarioInstance();
        instance.setId(id);
        instance.setTemplateKey("knowledge-qa-assistant");
        instance.setInstanceKey("knowledge-qa-assistant");
        instance.setDisplayName("企业知识问答助手");
        instance.setStatus(status);
        instance.setOwnerId(ownerId);
        instance.setAgentId(301L);
        instance.setAgentVersionId(401L);
        instance.setCreatedAt(LocalDateTime.now());
        instance.setUpdatedAt(LocalDateTime.now());
        instance.setDelFlag("0");
        return instance;
    }

    private AgentView agent(Long id, String status, String templateKey) {
        return new AgentView(
            id,
            "knowledge-qa-assistant",
            "企业知识问答助手",
            "",
            "knowledge",
            "agentscope_java",
            null,
            false,
            false,
            status,
            7L,
            0,
            Map.of("scenarioTemplateId", templateKey),
            401L,
            LocalDateTime.now(),
            LocalDateTime.now()
        );
    }
}
