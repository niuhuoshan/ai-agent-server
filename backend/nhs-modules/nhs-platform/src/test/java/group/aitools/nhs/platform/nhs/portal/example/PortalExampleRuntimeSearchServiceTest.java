package group.aitools.nhs.platform.nhs.portal.example;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.RuntimeExecutionKey;
import group.aitools.nhs.runtime.spi.RuntimeModelConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class PortalExampleRuntimeSearchServiceTest {

    @Test
    void searchesOnlyApprovedSynchronizedExamplesWithinFrozenDatasetGrant() {
        AgentChatBIExampleMapper mapper = mock(AgentChatBIExampleMapper.class);
        PortalExampleRuntimeSearchService service = new PortalExampleRuntimeSearchService(mapper);
        AgentChatBIExample row = new AgentChatBIExample();
        row.setId(11L);
        row.setDatasetId(3L);
        row.setUserQuery("查询金额");
        row.setSqlText("SELECT a.value FROM public.amounts a");
        row.setReviewStatus("approved");
        when(mapper.selectRuntimeCandidates(List.of(3L), null, "金额", 5))
            .thenReturn(List.of(row));
        when(mapper.incrementUseCount(11L)).thenReturn(1);

        Map<String, Object> result = service.search(request(), "金额", null, null);

        assertThat(result).containsEntry("index", "relational");
        assertThat(result.get("items")).asList().hasSize(1);
        verify(mapper).incrementUseCount(11L);
    }

    @Test
    void rejectsDatasetOutsideFrozenGrant() {
        AgentChatBIExampleMapper mapper = mock(AgentChatBIExampleMapper.class);
        PortalExampleRuntimeSearchService service = new PortalExampleRuntimeSearchService(mapper);

        assertThatThrownBy(() -> service.search(request(), "金额", 9L, 5))
            .hasMessageContaining("不在当前 Agent 运行授权范围内");
    }

    private AgentRunRequest request() {
        return new AgentRunRequest(
            new RuntimeExecutionKey("example-search", "trace-example"),
            7L, 10L, null, null, null, 20L, "分析Agent", "session-1", "金额",
            "", new RuntimeModelConfig("test", "test", null, "credential", Map.of()),
            null, 5, Map.of(), Map.of(
                "taskResourceSnapshot", Map.of(
                    "agentVersionId", 20L,
                    "resources", List.of(Map.of(
                        "resourceType", "dataset", "resourceId", 3L, "permission", "read"
                    ))
                )
            )
        );
    }
}
