package group.aitools.nhs.runtime.agentscope;

import group.aitools.nhs.runtime.spi.AgentResumeRequest;
import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.RuntimeResumeDecision;
import group.aitools.nhs.runtime.spi.RuntimeResumeMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class HarnessAgentInvocationTest {

    @Test
    void closesThePlatformToolLifecycleExactlyOnce() {
        HarnessAgent agent = mock(HarnessAgent.class);
        AtomicInteger closes = new AtomicInteger();
        HarnessAgentInvocation invocation = new HarnessAgentInvocation(
            agent, new ObjectMapper(), null, closes::incrementAndGet
        );

        invocation.close();
        invocation.close();

        verify(agent, times(1)).close();
        assertEquals(1, closes.get());
    }

    @Test
    void mapsApprovalResumeToAgentScopeConfirmationMessageAndRuntimeContext() {
        HarnessAgent agent = mock(HarnessAgent.class);
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.empty());
        HarnessAgentInvocation invocation = new HarnessAgentInvocation(agent, new ObjectMapper());
        AgentResumeRequest request = RuntimeFixtures.resumeRequest(RuntimeResumeDecision.APPROVE);

        invocation.resume(request).collectList().block();

        ArgumentCaptor<Msg> messageCaptor = ArgumentCaptor.forClass(Msg.class);
        ArgumentCaptor<RuntimeContext> contextCaptor = ArgumentCaptor.forClass(RuntimeContext.class);
        verify(agent).streamEvents(messageCaptor.capture(), contextCaptor.capture());

        Msg message = messageCaptor.getValue();
        assertEquals("Approved", message.getTextContent());
        assertEquals("reply-approval-1", message.getMetadata().get(Msg.METADATA_CONFIRM_REQUEST_REPLY_ID));
        List<?> results = assertInstanceOf(
            List.class,
            message.getMetadata().get(Msg.METADATA_CONFIRM_RESULTS)
        );
        ConfirmResult result = assertInstanceOf(ConfirmResult.class, results.getFirst());
        assertTrue(result.isConfirmed());
        assertEquals("tool-call-1", result.getToolCall().getId());
        assertEquals("write_file", result.getToolCall().getName());
        assertEquals("report.txt", result.getToolCall().getInput().get("path"));

        RuntimeContext context = contextCaptor.getValue();
        assertEquals("101", context.getUserId());
        assertEquals("session-1", context.getSessionId());
        assertEquals("execution-1", context.get("executionId"));
        assertEquals(Long.valueOf(201L), context.<Long>get("conversationId"));
        assertEquals(Long.valueOf(301L), context.<Long>get("taskId"));
        assertEquals(Long.valueOf(401L), context.<Long>get("runId"));
        assertEquals(Map.of("approvedBy", 101L), context.get("decisionMetadata"));
    }

    @Test
    void mapsRejectionWithoutChangingThePersistedPendingToolSnapshot() {
        HarnessAgent agent = mock(HarnessAgent.class);
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.empty());
        HarnessAgentInvocation invocation = new HarnessAgentInvocation(agent, new ObjectMapper());

        invocation.resume(RuntimeFixtures.resumeRequest(RuntimeResumeDecision.REJECT)).collectList().block();

        ArgumentCaptor<Msg> messageCaptor = ArgumentCaptor.forClass(Msg.class);
        verify(agent).streamEvents(messageCaptor.capture(), any(RuntimeContext.class));
        List<?> results = assertInstanceOf(
            List.class,
            messageCaptor.getValue().getMetadata().get(Msg.METADATA_CONFIRM_RESULTS)
        );
        ConfirmResult result = assertInstanceOf(ConfirmResult.class, results.getFirst());
        assertFalse(result.isConfirmed());
        assertEquals(Map.of("path", "report.txt", "content", "approved content"),
            result.getToolCall().getInput());
    }

    @Test
    void manualContinueDoesNotForgeAConfirmationResult() {
        HarnessAgent agent = mock(HarnessAgent.class);
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.empty());
        HarnessAgentInvocation invocation = new HarnessAgentInvocation(agent, new ObjectMapper());
        AgentResumeRequest request = new AgentResumeRequest(
            new group.aitools.nhs.runtime.spi.RuntimeExecutionKey("execution-1", "trace-1"),
            101L, 201L, 301L, 401L, 501L, "session-1", "manual-resume-401",
            RuntimeResumeDecision.APPROVE, Map.of(), Map.of(), RuntimeResumeMode.CONTINUE
        );

        invocation.resume(request).collectList().block();

        ArgumentCaptor<Msg> messageCaptor = ArgumentCaptor.forClass(Msg.class);
        ArgumentCaptor<RuntimeContext> contextCaptor = ArgumentCaptor.forClass(RuntimeContext.class);
        verify(agent).streamEvents(messageCaptor.capture(), contextCaptor.capture());
        assertTrue(messageCaptor.getValue().getTextContent().contains("Continue"));
        assertFalse(messageCaptor.getValue().getMetadata().containsKey(Msg.METADATA_CONFIRM_RESULTS));
        assertEquals("continue", contextCaptor.getValue().get("resumeMode"));
    }

    @Test
    void mapsEmbedImagesToTypedAgentScopeBlocksWithoutCopyingBytesIntoRuntimeContext() {
        HarnessAgent agent = mock(HarnessAgent.class);
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.empty());
        HarnessAgentInvocation invocation = new HarnessAgentInvocation(agent, new ObjectMapper());
        AgentRunRequest source = RuntimeFixtures.runRequest();
        AgentRunRequest request = new AgentRunRequest(
            source.executionKey(), source.userId(), source.conversationId(), source.taskId(),
            source.runId(), source.stepId(), source.agentVersionId(), source.agentName(),
            source.sessionId(), source.input(), source.systemPrompt(), source.model(),
            source.workspaceKey(), source.maxIterations(), source.authorizationSnapshot(),
            Map.of("embedMedia", List.of(Map.of(
                "mimeType", "image/png", "base64", "AQID"
            )))
        );

        invocation.stream(request).collectList().block();

        ArgumentCaptor<Msg> messageCaptor = ArgumentCaptor.forClass(Msg.class);
        ArgumentCaptor<RuntimeContext> contextCaptor = ArgumentCaptor.forClass(RuntimeContext.class);
        verify(agent).streamEvents(messageCaptor.capture(), contextCaptor.capture());
        assertEquals(2, messageCaptor.getValue().getContent().size());
        ImageBlock image = assertInstanceOf(
            ImageBlock.class, messageCaptor.getValue().getContent().get(1)
        );
        Base64Source media = assertInstanceOf(Base64Source.class, image.getSource());
        assertEquals("image/png", media.getMediaType());
        assertEquals("AQID", media.getData());
        assertNull(contextCaptor.getValue().get("embedMedia"));
    }
}
