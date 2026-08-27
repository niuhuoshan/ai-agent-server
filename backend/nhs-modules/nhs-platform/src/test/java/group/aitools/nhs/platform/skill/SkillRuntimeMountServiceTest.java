package group.aitools.nhs.platform.skill;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.runtime.spi.RuntimeExecutionKey;
import group.aitools.nhs.runtime.spi.RuntimeModelConfig;
import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.skill.domain.AgentSkillFile;
import group.aitools.nhs.platform.skill.mapper.SkillFileMapper;
import group.aitools.nhs.platform.skill.service.SkillDependencyRuntimeMountService;
import group.aitools.nhs.platform.skill.service.SkillRuntimeMountService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class SkillRuntimeMountServiceTest {

    @TempDir
    Path workspace;

    @Test
    void reusedWorkspaceDropsPreviousSkillFilesWhenSnapshotHasNoSkills() throws Exception {
        Path stale = workspace.resolve("skills/old-skill/SKILL.md");
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "stale", StandardCharsets.UTF_8);

        SkillRuntimeMountService service = new SkillRuntimeMountService(mock(SkillFileMapper.class));
        service.mount(request(Map.of("resourceBindings", List.of())), workspace);

        assertTrue(Files.isDirectory(workspace.resolve("skills")));
        assertFalse(Files.exists(stale));
    }

    @Test
    void mountsOnlyTheFrozenBundle() throws Exception {
        SkillFileMapper mapper = mock(SkillFileMapper.class);
        AgentSkillFile file = file("SKILL.md", "# frozen");
        when(mapper.selectFiles(10L, 11L)).thenReturn(List.of(file));
        SkillRuntimeMountService service = new SkillRuntimeMountService(mapper);

        Map<String, Object> snapshot = Map.of(
            "skillKey", "reviewer",
            "versionId", 11L,
            "fileBundleHash", bundleHash(file)
        );
        Map<String, Object> binding = Map.of(
            "resourceType", "skill",
            "resourceId", 10L,
            "permission", "use",
            "config", Map.of("resourceSnapshot", snapshot)
        );

        assertDoesNotThrow(() -> service.mount(
            request(Map.of("resourceBindings", List.of(binding))), workspace
        ));
        assertEquals(
            "# frozen",
            Files.readString(workspace.resolve("skills/reviewer/SKILL.md"), StandardCharsets.UTF_8)
        );
    }

    @Test
    void duplicateSkillKeysFailClosedAndLeaveNoPartialBundle() throws Exception {
        SkillFileMapper mapper = mock(SkillFileMapper.class);
        AgentSkillFile file = file("SKILL.md", "# frozen");
        when(mapper.selectFiles(10L, 11L)).thenReturn(List.of(file));
        SkillRuntimeMountService service = new SkillRuntimeMountService(mapper);
        Map<String, Object> snapshot = Map.of(
            "skillKey", "reviewer", "versionId", 11L, "fileBundleHash", bundleHash(file)
        );
        Map<String, Object> binding = Map.of(
            "resourceType", "skill", "resourceId", 10L, "permission", "use",
            "config", Map.of("resourceSnapshot", snapshot)
        );

        assertThrows(
            IllegalStateException.class,
            () -> service.mount(request(Map.of("resourceBindings", List.of(binding, binding))), workspace)
        );
        assertTrue(Files.isDirectory(workspace.resolve("skills")));
        assertFalse(Files.exists(workspace.resolve("skills/reviewer/SKILL.md")));
    }

    @Test
    void declaredDependenciesFailClosedWhenTheV84ConsumerIsUnavailable() throws Exception {
        SkillFileMapper mapper = mock(SkillFileMapper.class);
        AgentSkillFile file = file("SKILL.md", "# frozen");
        when(mapper.selectFiles(10L, 11L)).thenReturn(List.of(file));
        SkillRuntimeMountService service = new SkillRuntimeMountService(mapper);
        Map<String, Object> runtimeRequirements = Map.of(
            "dependencies", Map.of("python", List.of("requests==2.31.0"))
        );
        Map<String, Object> snapshot = Map.of(
            "skillKey", "reviewer", "versionId", 11L, "fileBundleHash", bundleHash(file),
            "runtimeRequirements", runtimeRequirements
        );
        Map<String, Object> binding = Map.of(
            "resourceType", "skill", "resourceId", 10L, "permission", "use",
            "config", Map.of("resourceSnapshot", snapshot)
        );

        assertThrows(
            IllegalStateException.class,
            () -> service.mount(request(Map.of("resourceBindings", List.of(binding))), workspace)
        );
        assertFalse(Files.exists(workspace.resolve("skills/reviewer/SKILL.md")));
    }

    @Test
    void frozenDependencyDeclarationIsPassedToTheV84RuntimeConsumer() throws Exception {
        SkillFileMapper mapper = mock(SkillFileMapper.class);
        AgentSkillFile file = file("SKILL.md", "# frozen");
        when(mapper.selectFiles(10L, 11L)).thenReturn(List.of(file));
        SkillDependencyRuntimeMountService dependencyMount = mock(SkillDependencyRuntimeMountService.class);
        SkillRuntimeMountService service = new SkillRuntimeMountService(mapper);
        service.setDependencyRuntimeMountService(dependencyMount);
        Map<String, Object> runtimeRequirements = Map.of(
            "dependencies", Map.of("python", List.of("requests==2.31.0"))
        );
        Map<String, Object> snapshot = Map.of(
            "skillKey", "reviewer", "versionId", 11L, "fileBundleHash", bundleHash(file),
            "runtimeRequirements", runtimeRequirements
        );
        Map<String, Object> binding = Map.of(
            "resourceType", "skill", "resourceId", 10L, "permission", "use",
            "config", Map.of("resourceSnapshot", snapshot)
        );

        service.mount(request(Map.of("resourceBindings", List.of(binding))), workspace);

        verify(dependencyMount).mount(
            10L, 11L, "reviewer", runtimeRequirements,
            workspace.resolve("skills/reviewer").toAbsolutePath().normalize()
        );
    }

    private AgentRunRequest request(Map<String, Object> attributes) {
        return new AgentRunRequest(
            new RuntimeExecutionKey("run-1", "trace-1"),
            1L, null, null, 2L, null, 3L, "agent", "session", "input", "system",
            new RuntimeModelConfig("openai", "model", null, "credential", Map.of()),
            "run-1", 12, Map.of(), attributes
        );
    }

    private AgentSkillFile file(String path, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        AgentSkillFile file = new AgentSkillFile();
        file.setPath(path);
        file.setFileKind("file");
        file.setContent(content);
        file.setContentEncoding("utf8");
        file.setContentBytes(null);
        file.setContentHash(ContentHashing.sha256(bytes));
        file.setSizeBytes(bytes.length);
        file.setDelFlag("0");
        return file;
    }

    private String bundleHash(AgentSkillFile file) {
        return ContentHashing.sha256(
            file.getPath() + "\n" + file.getFileKind() + "\n" + file.getContentHash()
        );
    }
}
