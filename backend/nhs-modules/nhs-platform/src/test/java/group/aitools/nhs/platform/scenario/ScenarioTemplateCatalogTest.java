package group.aitools.nhs.platform.scenario.service;

import group.aitools.nhs.platform.scenario.web.ScenarioTemplateViews;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class ScenarioTemplateCatalogTest {

    @Test
    void freezesAllNhsScenarioTemplatesAndTheirRequiredBindings() {
        var definitions = ScenarioTemplateCatalog.all();
        assertEquals(8, definitions.size());
        assertEquals(Set.of(
            "chatbi-business-analysis", "knowledge-qa-assistant", "ops-inspection-assistant",
            "finance-expense-analysis", "sales-customer-insight", "support-ticket-analysis",
            "hr-policy-qa", "legal-contract-review"
        ), definitions.stream().map(ScenarioTemplateCatalog.Definition::key).collect(java.util.stream.Collectors.toSet()));
        assertTrue(definitions.stream().allMatch(item -> !item.systemPrompt().isBlank()));
        assertTrue(definitions.stream().allMatch(item -> !item.sampleQuestions().isEmpty()));
        assertTrue(definitions.stream().allMatch(item -> item.requiredResources().stream().anyMatch(ScenarioTemplateViews.ResourceRequirement::required)));
        assertFalse(definitions.stream().anyMatch(item -> item.requiredResources().stream().anyMatch(item2 -> item2.type().equals("ragflow_dataset"))));
    }
}
