package group.aitools.nhs.platform.nhs.portal.prompt;

import group.aitools.nhs.platform.agent.service.AgentApplicationService;
import group.aitools.nhs.platform.model.domain.AgentModel;
import group.aitools.nhs.platform.model.mapper.AgentModelMapper;
import group.aitools.nhs.platform.model.service.HttpModelProviderClient;
import group.aitools.nhs.platform.model.service.ModelCredentialResolver;
import group.aitools.nhs.platform.model.service.ModelEndpointPolicy;
import group.aitools.nhs.platform.model.service.ModelProviderException;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class PortalPromptServiceModelTest {

    private final AgentApplicationService agentService = mock(AgentApplicationService.class);
    private final AgentModelMapper modelMapper = mock(AgentModelMapper.class);
    private final ModelEndpointPolicy endpointPolicy = mock(ModelEndpointPolicy.class);
    private final ModelCredentialResolver credentialResolver = mock(ModelCredentialResolver.class);
    private final HttpModelProviderClient modelClient = mock(HttpModelProviderClient.class);
    private final PortalPromptService service = new PortalPromptService(
        agentService,
        modelMapper,
        endpointPolicy,
        credentialResolver,
        modelClient,
        JsonMapper.builder().build()
    );

    @Test
    void testRendersVariablesAndInvokesExistingModelClient() {
        AgentModel model = readyModel("已生成财务摘要");
        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);

        Map<String, Object> result = service.test(
            "你是{部门}助手。配置：{\"mode\":\"strict\"}。筛选：{filters}",
            Map.of("部门", "财务", "filters", Map.of("year", 2026)),
            "汇总本月数据",
            null
        );

        verify(modelClient).complete(
            eq(model),
            eq(URI.create("https://model.example/v1")),
            eq("resolved-secret"),
            systemPrompt.capture(),
            userPrompt.capture()
        );
        assertEquals(
            "你是财务助手。配置：{\"mode\":\"strict\"}。筛选：{\"year\":2026}",
            systemPrompt.getValue()
        );
        assertEquals("汇总本月数据", userPrompt.getValue());
        assertEquals("succeeded", result.get("status"));
        assertEquals("已生成财务摘要", result.get("output"));
        assertEquals(systemPrompt.getValue(), result.get("rendered_prompt"));
        assertEquals(42L, result.get("model_id"));
        assertEquals("企业对话模型", result.get("model_name"));
        assertEquals("openai-compatible", result.get("provider"));
        assertInstanceOf(Long.class, result.get("elapsed_ms"));
    }

    @Test
    void defaultTestInputStillExercisesPromptAsSystemMessage() {
        AgentModel model = readyModel("示例响应");
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);

        service.test("只回答事实", Map.of(), "  ", null);

        verify(modelClient).complete(
            eq(model), any(), anyString(), eq("只回答事实"), userPrompt.capture()
        );
        assertTrue(userPrompt.getValue().contains("示例响应"));
    }

    @Test
    void missingRequiredVariablesAreRejectedBeforeModelResolution() {
        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.test("分析 {department} 的 {period} 数据", Map.of("period", "本月"), null, null)
        );

        assertEquals(400, exception.getCode());
        assertTrue(exception.getMessage().contains("department"));
        verify(modelMapper, never()).selectModels(anyString(), any(), any(), eq(false), any(Integer.class));
        verify(modelClient, never()).complete(any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void optimizeBuildsBoundedInstructionAndReturnsReviewableSuggestion() {
        AgentModel model = readyModel("你是财务助手。\n\n请分析 {department} 数据并说明证据来源。");
        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);

        Map<String, Object> result = service.optimize("分析 {department} 数据", null);

        verify(modelClient).complete(
            eq(model), any(), anyString(), systemPrompt.capture(), userPrompt.capture()
        );
        assertTrue(systemPrompt.getValue().contains("只返回可以直接使用的完整优化提示词"));
        assertTrue(systemPrompt.getValue().contains("所有 {variable} 占位符"));
        assertTrue(userPrompt.getValue().contains("分析 {department} 数据"));
        assertEquals("succeeded", result.get("status"));
        assertEquals(
            "你是财务助手。\n\n请分析 {department} 数据并说明证据来源。",
            result.get("optimized_content")
        );
    }

    @Test
    void optimizationRejectsOutputThatDropsRequiredVariables() {
        readyModel("请分析财务数据并说明证据来源。");

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.optimize("分析 {department} 数据", null)
        );

        assertEquals(502, exception.getCode());
        assertTrue(exception.getMessage().contains("department"));
    }

    @Test
    void noActiveChatModelIsExplicitlyUnavailable() {
        when(modelMapper.selectModels("chat", null, null, false, 1)).thenReturn(List.of());

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.test("直接回答", Map.of(), "你好", null)
        );

        assertEquals(501, exception.getCode());
        assertTrue(exception.getMessage().contains("未配置"));
        verify(modelClient, never()).complete(any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void missingModelEndpointIsExplicitlyUnavailable() {
        AgentModel model = model();
        when(modelMapper.selectModels("chat", null, null, false, 1)).thenReturn(List.of(model));
        when(endpointPolicy.normalize(model.getProviderType(), model.getEndpointUrl()))
            .thenThrow(new IllegalStateException("endpoint is not configured"));

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.optimize("直接回答", null)
        );

        assertEquals(501, exception.getCode());
        assertTrue(exception.getMessage().contains("端点配置不可用"));
        verify(credentialResolver, never()).resolve(anyString());
        verify(modelClient, never()).complete(any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void missingModelCredentialIsExplicitlyUnavailable() {
        AgentModel model = model();
        when(modelMapper.selectModels("chat", null, null, false, 1)).thenReturn(List.of(model));
        when(endpointPolicy.normalize(model.getProviderType(), model.getEndpointUrl()))
            .thenReturn(URI.create("https://model.example/v1"));
        when(credentialResolver.resolve(model.getCredentialRef()))
            .thenThrow(new IllegalStateException("credential is not configured"));

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.test("直接回答", Map.of(), "你好", null)
        );

        assertEquals(501, exception.getCode());
        assertTrue(exception.getMessage().contains("凭证未就绪"));
        verify(modelClient, never()).complete(any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void emptyProviderOutputIsNotReportedAsSuccess() {
        readyModel("   ");

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.test("直接回答", Map.of(), "你好", null)
        );

        assertEquals(502, exception.getCode());
        assertTrue(exception.getMessage().contains("未返回有效内容"));
    }

    @Test
    void unexpectedProviderFailureDoesNotLeakItsMessage() {
        AgentModel model = model();
        when(modelMapper.selectModels("chat", null, null, false, 1)).thenReturn(List.of(model));
        when(endpointPolicy.normalize(model.getProviderType(), model.getEndpointUrl()))
            .thenReturn(URI.create("https://model.example/v1"));
        when(credentialResolver.resolve(model.getCredentialRef())).thenReturn("resolved-secret");
        when(modelClient.complete(any(), any(), anyString(), anyString(), anyString()))
            .thenThrow(new IllegalStateException("Bearer sk-test-redaction-sentinel"));

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.test("直接回答", Map.of(), "你好", null)
        );

        assertEquals(502, exception.getCode());
        assertTrue(exception.getMessage().contains("模型调用失败"));
        assertFalse(exception.getMessage().contains("sk-sensitive"));
        assertFalse(exception.getMessage().contains("resolved-secret"));
    }

    @Test
    void safeProviderFailureIsPropagatedWithCredentialValueRedacted() {
        AgentModel model = model();
        when(modelMapper.selectModels("chat", null, null, false, 1)).thenReturn(List.of(model));
        when(endpointPolicy.normalize(model.getProviderType(), model.getEndpointUrl()))
            .thenReturn(URI.create("https://model.example/v1"));
        when(credentialResolver.resolve(model.getCredentialRef())).thenReturn("resolved-secret");
        when(modelClient.complete(any(), any(), anyString(), anyString(), anyString()))
            .thenThrow(new ModelProviderException("供应商鉴权失败：resolved-secret"));

        ServiceException exception = assertThrows(
            ServiceException.class,
            () -> service.test("直接回答", Map.of(), "你好", null)
        );

        assertEquals(502, exception.getCode());
        assertTrue(exception.getMessage().contains("供应商鉴权失败"));
        assertTrue(exception.getMessage().contains("[REDACTED]"));
        assertFalse(exception.getMessage().contains("resolved-secret"));
    }

    private AgentModel readyModel(String response) {
        AgentModel model = model();
        when(modelMapper.selectModels("chat", null, null, false, 1)).thenReturn(List.of(model));
        when(endpointPolicy.normalize(model.getProviderType(), model.getEndpointUrl()))
            .thenReturn(URI.create("https://model.example/v1"));
        when(credentialResolver.resolve(model.getCredentialRef())).thenReturn("resolved-secret");
        when(modelClient.complete(any(), any(), anyString(), anyString(), anyString()))
            .thenReturn(response);
        return model;
    }

    private AgentModel model() {
        AgentModel model = new AgentModel();
        model.setId(42L);
        model.setModelKey("enterprise-chat");
        model.setDisplayName("企业对话模型");
        model.setProviderType("openai-compatible");
        model.setModelName("provider-chat-model");
        model.setModelType("chat");
        model.setEndpointUrl("https://model.example/v1");
        model.setCredentialRef("env:MODEL_API_KEY");
        model.setStatus("active");
        return model;
    }
}
