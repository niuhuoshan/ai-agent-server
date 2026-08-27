package group.aitools.nhs.platform.workflow;

import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.workflow.mapper.WorkflowCatalogMapper;
import group.aitools.nhs.platform.workflow.persistence.row.WorkflowTemplateRow;
import group.aitools.nhs.platform.workflow.service.WorkflowCatalogService;
import group.aitools.nhs.platform.workflow.service.WorkflowGraphValidator;
import group.aitools.nhs.platform.workflow.service.WorkflowTaskBinding;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class WorkflowGraphValidatorTest {

    private static final String GRAPH = "{\"maxParallelism\":3,\"nodes\":["
        + "{\"dependsOn\":[],\"instruction\":\"Backend.\",\"key\":\"backend\",\"role\":\"backend\",\"sequence\":1,\"type\":\"agent\"},"
        + "{\"dependsOn\":[],\"instruction\":\"Frontend.\",\"key\":\"frontend\",\"role\":\"frontend\",\"sequence\":2,\"type\":\"agent\"},"
        + "{\"dependsOn\":[],\"instruction\":\"Test.\",\"key\":\"test\",\"role\":\"test\",\"sequence\":3,\"type\":\"agent\"},"
        + "{\"dependsOn\":[\"backend\",\"frontend\",\"test\"],\"instruction\":\"Summary.\",\"key\":\"summary\",\"sequence\":4,\"type\":\"aggregate\"}],"
        + "\"roles\":[{\"key\":\"backend\",\"name\":\"Backend Agent\"},{\"key\":\"frontend\",\"name\":\"Frontend Agent\"},{\"key\":\"test\",\"name\":\"Test Agent\"}],"
        + "\"schemaVersion\":1,\"templateKey\":\"delivery_team\"}";

    private WorkflowCatalogMapper mapper;
    private WorkflowGraphValidator validator;
    private WorkflowCatalogService service;

    @BeforeEach
    void setUp() {
        mapper = mock(WorkflowCatalogMapper.class);
        validator = new WorkflowGraphValidator(JsonMapper.builder().build());
        service = new WorkflowCatalogService(mapper, validator);
    }

    @Test
    void acceptsOnlyCompleteAuthorizedRoleBinding() {
        WorkflowTemplateRow row = row("delivery_team", GRAPH, ContentHashing.sha256(GRAPH));
        when(mapper.selectVersion(101L)).thenReturn(row);

        WorkflowTaskBinding binding = service.validateTaskBinding(
            "multi_agent_template", 101L, 201L,
            Map.of("backend", 201L, "frontend", 202L, "test", 203L)
        );

        assertEquals(3, binding.agentVersions().size());
        assertEquals(3, binding.template().maxParallelism());
        assertThrows(ServiceException.class, () -> service.validateTaskBinding(
            "multi_agent_template", 101L, 201L,
            Map.of("backend", 201L, "frontend", 202L)
        ));
        assertThrows(ServiceException.class, () -> service.validateTaskBinding(
            "multi_agent_template", 101L, 999L,
            Map.of("backend", 201L, "frontend", 202L, "test", 203L)
        ));
    }

    @Test
    void rejectsHashTamperingAndArbitraryRehashedDag() {
        WorkflowTemplateRow tampered = row(
            "delivery_team", GRAPH.replace("Backend.", "Injected."), ContentHashing.sha256(GRAPH)
        );
        assertThrows(ServiceException.class, () -> validator.validate(tampered));

        String arbitrary = GRAPH.replace("\"delivery_team\"", "\"free_dag\"");
        WorkflowTemplateRow rehashed = row(
            "free_dag", arbitrary, ContentHashing.sha256(arbitrary)
        );
        assertThrows(ServiceException.class, () -> validator.validate(rehashed));
    }

    @Test
    void singleAgentModeRejectsWorkflowInjection() {
        assertThrows(ServiceException.class, () -> service.validateTaskBinding(
            "single_agent", 101L, 201L, Map.of("backend", 201L)
        ));
    }

    private WorkflowTemplateRow row(String key, String graph, String hash) {
        WorkflowTemplateRow row = new WorkflowTemplateRow();
        row.setWorkflowId(1L);
        row.setWorkflowKey(key);
        row.setName(key);
        row.setWorkflowType("fixed_template");
        row.setWorkflowStatus("active");
        row.setVersionId(101L);
        row.setVersionNo(1);
        row.setGraphJson(graph);
        row.setRuntimePolicyJson(
            "{\"failFast\":true,\"maxDependencyBytes\":65536,\"maxParallelism\":3}"
        );
        row.setContentHash(hash);
        row.setVersionStatus("published");
        row.setPublishedAt(LocalDateTime.now());
        return row;
    }
}
