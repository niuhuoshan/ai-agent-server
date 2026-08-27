package group.aitools.nhs.platform.artifact;

import group.aitools.nhs.platform.artifact.web.AcceptanceDecisionRequest;
import group.aitools.nhs.platform.artifact.web.RegisterArtifactRequest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class ArtifactRequestDeserializationTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void clientCannotInjectArtifactLifecycleOrAcceptanceReviewerFields() {
        assertThrows(RuntimeException.class, () -> jsonMapper.readValue(
            """
                {"artifactType":"document","name":"report.pdf","storageType":"local",
                 "storageRef":"tasks/10/report.pdf","contentHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                 "status":"available","versionNo":99,"createdBy":1}
                """,
            RegisterArtifactRequest.class
        ));
        assertThrows(RuntimeException.class, () -> jsonMapper.readValue(
            """
                {"idempotencyKey":"accept-1","artifactIds":[700],"result":"passed",
                 "reviewerId":1,"taskStatus":"completed","reworkNo":0}
                """,
            AcceptanceDecisionRequest.class
        ));
    }
}
