package group.aitools.nhs.platform.sandbox;

import group.aitools.nhs.platform.sandbox.service.SandboxSkillManifest;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class SandboxSkillManifestTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void canonicalHashSurvivesJsonbObjectKeyReordering() {
        String hash = "a".repeat(64);
        SandboxSkillManifest.Normalized first = SandboxSkillManifest.fromJson(
            "{\"version\":1,\"workspaceKey\":\"run-1\",\"skills\":["
                + "{\"skillId\":10,\"versionId\":11,\"skillKey\":\"reviewer\","
                + "\"fileBundleHash\":\"" + hash + "\"}]}" , mapper
        );
        SandboxSkillManifest.Normalized reordered = SandboxSkillManifest.fromJson(
            "{\"skills\":[{\"fileBundleHash\":\"" + hash
                + "\",\"skillKey\":\"reviewer\",\"versionId\":11,\"skillId\":10}],"
                + "\"workspaceKey\":\"run-1\",\"version\":1}", mapper
        );

        assertEquals(first.json(), reordered.json());
        assertEquals(first.hash(), reordered.hash());
    }

    @Test
    void extractsOnlyFrozenSkillBindingsAndNormalizesDependencies() {
        SandboxSkillManifest.Normalized manifest = SandboxSkillManifest.fromAttributes(
            Map.of("resourceBindings", List.of(
                Map.of(
                    "resourceType", "skill",
                    "resourceId", 10L,
                    "config", Map.of("resourceSnapshot", Map.of(
                        "versionId", 11L,
                        "skillKey", "reviewer",
                        "fileBundleHash", "b".repeat(64),
                        "runtimeRequirements", Map.of(
                            "dependencies", Map.of("python", List.of("z==1", "a==1"))
                        )
                    ))
                ),
                Map.of("resourceType", "connector", "resourceId", 20L)
            )),
            "run-1", mapper
        );

        assertEquals(1, manifest.entries().size());
        assertTrue(manifest.json().contains("a==1"));
        assertTrue(manifest.json().indexOf("a==1") < manifest.json().indexOf("z==1"));
    }

    @Test
    void rejectsDuplicateSkillKeys() {
        String hash = "c".repeat(64);
        Map<String, Object> entry = Map.of(
            "skillId", 10L, "versionId", 11L, "skillKey", "reviewer", "fileBundleHash", hash
        );

        assertThrows(ServiceException.class, () -> SandboxSkillManifest.normalize(
            List.of(entry, entry), "run-1", mapper
        ));
    }

    @Test
    void rejectsMissingOrUnsupportedObjectVersion() {
        ServiceException missing = assertThrows(ServiceException.class, () ->
            SandboxSkillManifest.fromJson("{\"skills\":[]}", mapper)
        );
        ServiceException unsupported = assertThrows(ServiceException.class, () ->
            SandboxSkillManifest.fromJson("{\"version\":2,\"skills\":[]}", mapper)
        );

        assertEquals(400, missing.getCode());
        assertEquals(400, unsupported.getCode());
    }

    @Test
    void keepsOnlyEmptyLegacyArrayCompatibility() {
        assertTrue(SandboxSkillManifest.fromJson("[]", mapper).empty());
        assertThrows(ServiceException.class, () -> SandboxSkillManifest.fromJson(
            "[{\"skillId\":10}]", mapper
        ));
    }
}
