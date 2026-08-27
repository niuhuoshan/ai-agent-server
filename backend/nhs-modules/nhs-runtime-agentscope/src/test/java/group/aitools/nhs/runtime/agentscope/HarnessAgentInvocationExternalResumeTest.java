package group.aitools.nhs.runtime.agentscope;

import group.aitools.nhs.runtime.spi.AgentResumeRequest;
import group.aitools.nhs.runtime.spi.RuntimeExecutionKey;
import group.aitools.nhs.runtime.spi.RuntimeResumeDecision;
import group.aitools.nhs.runtime.spi.RuntimeResumeMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class HarnessAgentInvocationExternalResumeTest {

    @Test
    void externalResumeSuppliesCorrelatedToolResultsInsteadOfApprovalMetadata() {
        HarnessAgent agent = mock(HarnessAgent.class);
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.empty());
        HarnessAgentInvocation invocation = new HarnessAgentInvocation(agent, new ObjectMapper());
        AgentResumeRequest request = new AgentResumeRequest(
            new RuntimeExecutionKey("run-10", "trace-10"), 9L, null, 10L, 11L, 12L,
            "session", "reply-1", RuntimeResumeDecision.APPROVE,
            List.of(
                Map.of(
                    "id", "call-1", "name", "platform_tool_500", "succeeded", true,
                    "result", Map.of("status", "succeeded", "stdout", "ok")
                ),
                Map.of(
                    "id", "call-2", "name", "platform_tool_500", "succeeded", false,
                    "result", Map.of("status", "failed", "failureCode", "NON_ZERO_EXIT")
                )
            ),
            Map.of("source", "sandbox_runner"), RuntimeResumeMode.EXTERNAL_EXECUTION
        );
        ArgumentCaptor<Msg> message = ArgumentCaptor.forClass(Msg.class);

        invocation.resume(request).blockLast();

        verify(agent).streamEvents(message.capture(), any(RuntimeContext.class));
        assertInstanceOf(ToolResultMessage.class, message.getValue());
        List<ToolResultBlock> results = message.getValue().getContentBlocks(ToolResultBlock.class);
        assertEquals(2, results.size());
        assertEquals("call-1", results.get(0).getId());
        assertEquals(ToolResultState.SUCCESS, results.get(0).getState());
        assertEquals(ToolResultState.ERROR, results.get(1).getState());
    }
}
