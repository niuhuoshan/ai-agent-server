package group.aitools.nhs.platform.agent;

import group.aitools.nhs.platform.agent.web.CreateAgentRequest;
import group.aitools.nhs.platform.agent.web.SaveAgentVersionRequest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class AgentRequestDeserializationTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void systemFlagsRawSecretsAndUnknownFieldsAreRejected() {
        String definition = """
            {
              "agentKey":"assistant",
              "name":"Assistant",
              "agentType":"assistant",
              "defaultAgent":false,
              "sortOrder":0,
              "isSystem":true
            }
            """;
        String version = """
            {
              "systemPrompt":"system",
              "modelId":20,
              "runtimeConfig":{},
              "welcomeConfig":{},
              "routingTags":[],
              "tools":[],
              "skills":[],
              "knowledgeBases":[],
              "apiKey":"raw-secret"
            }
            """;

        assertThrows(RuntimeException.class, () -> jsonMapper.readValue(definition, CreateAgentRequest.class));
        assertThrows(RuntimeException.class, () -> jsonMapper.readValue(version, SaveAgentVersionRequest.class));
    }
}
