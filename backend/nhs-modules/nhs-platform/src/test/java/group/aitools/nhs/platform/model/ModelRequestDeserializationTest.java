package group.aitools.nhs.platform.model;

import group.aitools.nhs.platform.model.web.CreateModelRequest;
import group.aitools.nhs.platform.model.web.TestModelConfigRequest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class ModelRequestDeserializationTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void legacyCredentialReferencesAndUnknownFieldsAreRejectedDuringDeserialization() {
        String createPayload = """
            {
              "modelKey":"chat-main",
              "displayName":"Chat",
              "providerType":"openai",
              "modelName":"gpt-test",
              "modelType":"chat",
              "apiKey":"raw-secret",
              "status":"active",
              "apiKey":"raw-secret"
            }
            """;
        String testPayload = """
            {
              "providerType":"openai",
              "modelName":"gpt-test",
              "modelType":"chat",
              "credentialRef":"env:OPENAI_API_KEY"
            }
            """;

        assertThrows(RuntimeException.class, () -> jsonMapper.readValue(createPayload, CreateModelRequest.class));
        assertThrows(RuntimeException.class, () -> jsonMapper.readValue(testPayload, TestModelConfigRequest.class));
    }
}
