package group.aitools.nhs.platform.agent;

import group.aitools.nhs.platform.agent.domain.AgentDefinition;
import group.aitools.nhs.platform.agent.domain.AgentDefinitionVersion;
import group.aitools.nhs.platform.agent.mapper.AgentDefinitionMapper;
import group.aitools.nhs.platform.agent.mapper.AgentDefinitionVersionMapper;
import group.aitools.nhs.platform.agent.service.AgentWelcomeCardService;
import group.aitools.nhs.platform.agent.web.WelcomeCardView;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.domain.PrincipalType;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.nhs.portal.chatbi.PortalChatBIModelGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class AgentWelcomeCardServiceTest {

    private static final CurrentPrincipal MEMBER = new CurrentPrincipal(
        7L, "alice", PrincipalType.HUMAN, Set.of(PlatformRole.MEMBER)
    );

    private CurrentPrincipalProvider principalProvider;
    private AuthorizationEnforcer authorizationEnforcer;
    private AgentDefinitionMapper definitionMapper;
    private AgentDefinitionVersionMapper versionMapper;
    private AgentWelcomeCardService service;

    @BeforeEach
    void setUp() {
        principalProvider = mock(CurrentPrincipalProvider.class);
        authorizationEnforcer = mock(AuthorizationEnforcer.class);
        definitionMapper = mock(AgentDefinitionMapper.class);
        versionMapper = mock(AgentDefinitionVersionMapper.class);
        PortalChatBIModelGateway modelGateway = mock(PortalChatBIModelGateway.class);
        service = new AgentWelcomeCardService(
            principalProvider, authorizationEnforcer, definitionMapper, versionMapper,
            JsonMapper.builder().build(), modelGateway
        );
        when(principalProvider.currentPrincipal()).thenReturn(MEMBER);
        when(definitionMapper.selectDefinitionById(10L)).thenReturn(agent());
    }

    @Test
    void manualCardsComeOnlyFromTheActivePublishedVersion() {
        when(versionMapper.selectVersion(10L, 20L)).thenReturn(version("""
            {"enabled":true,"mode":"manual","cards":[
              {"icon":"chart","title":"趋势","subtitle":"查看指标趋势","prompt":"查看本月趋势"},
              {"icon":"alert","title":"异常","subtitle":"定位异常指标","prompt":"排查今日异常"},
              {"icon":"report","title":"日报","subtitle":"汇总今日数据","prompt":"生成今日经营日报"}
            ]}
            """));

        List<WelcomeCardView> cards = service.list(10L);

        assertEquals(3, cards.size());
        assertEquals("查看本月趋势", cards.getFirst().prompt());
        ArgumentCaptor<PermissionContext> context = ArgumentCaptor.forClass(PermissionContext.class);
        verify(authorizationEnforcer).requireAllowed(eq(MEMBER), context.capture());
        assertEquals("agent_version", context.getValue().resourceType());
        assertEquals(20L, context.getValue().resourceId());
        assertEquals("use", context.getValue().action());
        assertEquals(null, context.getValue().taskId());
    }

    @Test
    void incompleteManualCardsFailClosed() {
        when(versionMapper.selectVersion(10L, 20L)).thenReturn(version("""
            {"enabled":true,"mode":"manual","cards":[
              {"icon":"chat","title":"问题","subtitle":"说明","prompt":"开始提问"}
            ]}
            """));

        assertEquals(List.of(), service.list(10L));
    }

    @Test
    void legacyMessageAndSuggestionsRemainClickable() {
        when(versionMapper.selectVersion(10L, 20L)).thenReturn(version("""
            {"message":"欢迎使用经营助手","showSuggestions":true,
             "suggestions":["查看日报","排查异常"]}
            """));

        List<WelcomeCardView> cards = service.list(10L);

        assertEquals(2, cards.size());
        assertEquals("欢迎使用经营助手", cards.getFirst().subtitle());
        assertEquals("查看日报", cards.getFirst().prompt());
    }

    private AgentDefinition agent() {
        AgentDefinition definition = new AgentDefinition();
        definition.setId(10L);
        definition.setAgentKey("operations-agent");
        definition.setName("经营助手");
        definition.setStatus("active");
        definition.setPublishedVersionId(20L);
        definition.setDelFlag("0");
        return definition;
    }

    private AgentDefinitionVersion version(String welcomeConfigJson) {
        AgentDefinitionVersion version = new AgentDefinitionVersion();
        version.setId(20L);
        version.setAgentId(10L);
        version.setVersionNo(3);
        version.setSystemPrompt("协助经营分析");
        version.setWelcomeConfigJson(welcomeConfigJson);
        version.setStatus("published");
        return version;
    }
}
