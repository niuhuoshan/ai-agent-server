package group.aitools.nhs.platform.agent;

import group.aitools.nhs.platform.agent.domain.AgentDefinitionVersion;
import group.aitools.nhs.platform.agent.persistence.row.AgentVersionBindingRow;
import group.aitools.nhs.platform.agent.service.AgentVersionContentHasher;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@Tag("dev")
class AgentVersionContentHasherTest {

    private final AgentVersionContentHasher hasher = new AgentVersionContentHasher(
        JsonMapper.builder().build()
    );

    @Test
    void hashIsIndependentOfBindingOrderButCoversFrozenConfig() {
        AgentDefinitionVersion version = version();
        AgentVersionBindingRow tool = binding("tool", 20L, "use", "{\"b\":2,\"a\":1}");
        AgentVersionBindingRow knowledge = binding("knowledge_base", 10L, "read", "{}");

        String first = hasher.hash(version, List.of(tool, knowledge));
        String reordered = hasher.hash(version, List.of(knowledge, tool));
        tool.setConfigJson("{\"a\":1,\"b\":2}");
        String reorderedJson = hasher.hash(version, List.of(tool, knowledge));
        tool.setConfigJson("{\"a\":1,\"b\":3}");
        String changed = hasher.hash(version, List.of(tool, knowledge));

        assertEquals(first, reordered);
        assertEquals(first, reorderedJson);
        assertNotEquals(first, changed);
    }

    private AgentDefinitionVersion version() {
        AgentDefinitionVersion version = new AgentDefinitionVersion();
        version.setSystemPrompt("system");
        version.setModelId(1L);
        version.setRuntimeConfigJson("{\"z\":1,\"a\":2}");
        version.setWelcomeConfigJson("{}");
        version.setRoutingTagsJson("[\"coding\"]");
        return version;
    }

    private AgentVersionBindingRow binding(String type, Long id, String permission, String config) {
        AgentVersionBindingRow row = new AgentVersionBindingRow();
        row.setResourceType(type);
        row.setResourceId(id);
        row.setPermission(permission);
        row.setConfigJson(config);
        return row;
    }
}
