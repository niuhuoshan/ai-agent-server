package group.aitools.nhs.platform.skill;

import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.skill.domain.AgentSkillDependencyInstall;
import group.aitools.nhs.platform.skill.mapper.SkillDependencyInstallMapper;
import group.aitools.nhs.platform.skill.service.SkillDependencyRuntimeMountService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class SkillDependencyRuntimeMountServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temp;

    @Test
    void onlySucceededMatchingInstallationIsCopiedIntoReservedSkillDirectory() throws Exception {
        SkillDependencyInstallMapper mapper = mock(SkillDependencyInstallMapper.class);
        Path cache = temp.resolve("cache");
        Map<String, Object> requirements = Map.of(
            "dependencies", Map.of(
                "python", List.of("requests==2.31.0"),
                "node", List.of("lodash@4.17.21")
            )
        );
        String hash = hash(requirements);
        String relative = "skill-10/version-11/" + hash.substring(0, 16);
        Files.createDirectories(cache.resolve(relative).resolve("python"));
        Files.createDirectories(cache.resolve(relative).resolve("node/lib"));
        Files.writeString(cache.resolve(relative).resolve("python/requests.py"), "cached-python");
        Files.writeString(cache.resolve(relative).resolve("node/lib/index.js"), "cached-node");

        AgentSkillDependencyInstall state = state(10L, 11L, hash, relative, "succeeded");
        when(mapper.select(11L, hash)).thenReturn(state);
        SkillDependencyRuntimeMountService service = new SkillDependencyRuntimeMountService(
            mapper, JSON, cache.toString()
        );
        Path skillRoot = temp.resolve("workspace/skills/reviewer");
        Files.createDirectories(skillRoot);

        service.mount(10L, 11L, "reviewer", requirements, skillRoot);

        assertEquals(
            "cached-python",
            Files.readString(skillRoot.resolve(".agent-dependencies/python/requests.py"))
        );
        assertEquals(
            "cached-node",
            Files.readString(skillRoot.resolve(".agent-dependencies/node/lib/index.js"))
        );
        verify(mapper).select(eq(11L), eq(hash));
    }

    @Test
    void missingOrFailedInstallationFailsClosedWithoutCreatingInjectionDirectory() throws Exception {
        SkillDependencyInstallMapper mapper = mock(SkillDependencyInstallMapper.class);
        Map<String, Object> requirements = Map.of(
            "dependencies", Map.of("python", List.of("requests==2.31.0"))
        );
        String hash = hash(requirements);
        when(mapper.select(11L, hash)).thenReturn(null);
        SkillDependencyRuntimeMountService service = new SkillDependencyRuntimeMountService(
            mapper, JSON, temp.resolve("cache").toString()
        );
        Path skillRoot = temp.resolve("workspace/skills/reviewer");
        Files.createDirectories(skillRoot);

        assertThrows(
            IllegalStateException.class,
            () -> service.mount(10L, 11L, "reviewer", requirements, skillRoot)
        );
        assertFalse(Files.exists(
            skillRoot.resolve(SkillDependencyRuntimeMountService.INJECTED_DIRECTORY)
        ));

        String relative = "skill-10/version-11/" + hash.substring(0, 16);
        AgentSkillDependencyInstall failed = state(10L, 11L, hash, relative, "failed");
        when(mapper.select(11L, hash)).thenReturn(failed);
        assertThrows(
            IllegalStateException.class,
            () -> service.mount(10L, 11L, "reviewer", requirements, skillRoot)
        );
    }

    @Test
    void tamperedInstallRootOrCacheSymlinkIsRejected() throws Exception {
        SkillDependencyInstallMapper mapper = mock(SkillDependencyInstallMapper.class);
        Map<String, Object> requirements = Map.of(
            "dependencies", Map.of("python", List.of("requests==2.31.0"))
        );
        String hash = hash(requirements);
        String expected = "skill-10/version-11/" + hash.substring(0, 16);
        AgentSkillDependencyInstall state = state(10L, 11L, hash, "../outside", "succeeded");
        when(mapper.select(11L, hash)).thenReturn(state);
        SkillDependencyRuntimeMountService service = new SkillDependencyRuntimeMountService(
            mapper, JSON, temp.resolve("cache").toString()
        );
        Path skillRoot = temp.resolve("workspace/skills/reviewer");
        Files.createDirectories(skillRoot);

        assertThrows(
            IllegalStateException.class,
            () -> service.mount(10L, 11L, "reviewer", requirements, skillRoot)
        );

        Path cache = temp.resolve("cache");
        Files.createDirectories(cache.resolve(expected));
        Path outside = temp.resolve("outside");
        Files.createDirectories(outside);
        Files.createSymbolicLink(cache.resolve(expected).resolve("python"), outside);
        state.setInstallRoot(expected);
        when(mapper.select(11L, hash)).thenReturn(state);
        assertThrows(
            IllegalStateException.class,
            () -> service.mount(10L, 11L, "reviewer", requirements, skillRoot)
        );
    }

    private String hash(Map<String, Object> requirements) {
        @SuppressWarnings("unchecked")
        Map<String, Object> dependencies = (Map<String, Object>) requirements.get("dependencies");
        return ContentHashing.sha256(JSON.writeValueAsString(new TreeMap<>(dependencies)));
    }

    private AgentSkillDependencyInstall state(
        Long skillId,
        Long versionId,
        String hash,
        String relativeRoot,
        String status
    ) {
        AgentSkillDependencyInstall state = new AgentSkillDependencyInstall();
        state.setSkillId(skillId);
        state.setVersionId(versionId);
        state.setDependencyHash(hash);
        state.setInstallRoot(relativeRoot);
        state.setStatus(status);
        state.setAttemptNo(1);
        return state;
    }
}
