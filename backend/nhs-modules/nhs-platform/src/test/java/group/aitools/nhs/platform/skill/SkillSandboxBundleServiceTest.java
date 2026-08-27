package group.aitools.nhs.platform.skill;

import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.sandbox.persistence.row.SandboxJobRow;
import group.aitools.nhs.platform.sandbox.service.SandboxSkillManifest;
import group.aitools.nhs.platform.skill.domain.AgentSkillFile;
import group.aitools.nhs.platform.skill.mapper.SkillDependencyInstallMapper;
import group.aitools.nhs.platform.skill.mapper.SkillFileMapper;
import group.aitools.nhs.platform.skill.service.SkillSandboxBundleService;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class SkillSandboxBundleServiceTest {

    @TempDir
    Path dependencyRoot;

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void writesUtf8UstarPathsUsingByteAwarePrefixSplitting() throws Exception {
        SkillFileMapper fileMapper = mock(SkillFileMapper.class);
        String longPath = "目录".repeat(20) + "/说明.txt";
        List<AgentSkillFile> files = List.of(
            file("SKILL.md", "# frozen\n"),
            file(longPath, "说明\n")
        );
        when(fileMapper.selectFiles(10L, 11L)).thenReturn(files);
        SkillSandboxBundleService service = service(fileMapper);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        service.writeBundle(job(List.of(entry(10L, 11L, "reviewer", files))), output);

        assertTrue(tarPaths(output.toByteArray()).contains("skills/reviewer/" + longPath));
    }

    @Test
    void rejectsAFileThatIsAlsoTheParentOfAnotherEntry() {
        SkillFileMapper fileMapper = mock(SkillFileMapper.class);
        List<AgentSkillFile> files = List.of(
            file("SKILL.md", "# frozen\n"),
            file("scripts", "not a directory"),
            file("scripts/run.py", "print('no')\n")
        );
        when(fileMapper.selectFiles(10L, 11L)).thenReturn(files);

        assertThrows(ServiceException.class, () -> service(fileMapper).writeBundle(
            job(List.of(entry(10L, 11L, "reviewer", files))), new ByteArrayOutputStream()
        ));
    }

    @Test
    void leavesTheResponseUntouchedWhenALaterSkillCannotBeArchived() {
        SkillFileMapper fileMapper = mock(SkillFileMapper.class);
        List<AgentSkillFile> files = List.of(file("SKILL.md", "# frozen\n"));
        when(fileMapper.selectFiles(10L, 11L)).thenReturn(files);
        when(fileMapper.selectFiles(20L, 21L)).thenReturn(List.of());
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        assertThrows(ServiceException.class, () -> service(fileMapper).writeBundle(
            job(List.of(
                entry(10L, 11L, "alpha", files),
                Map.of(
                    "skillId", 20L,
                    "versionId", 21L,
                    "skillKey", "broken",
                    "bundleHash", "d".repeat(64)
                )
            )),
            output
        ));
        assertEquals(0, output.size());
    }

    private SkillSandboxBundleService service(SkillFileMapper fileMapper) {
        return new SkillSandboxBundleService(
            fileMapper, mock(SkillDependencyInstallMapper.class), mapper,
            dependencyRoot.toString()
        );
    }

    private SandboxJobRow job(List<Map<String, Object>> entries) {
        SandboxSkillManifest.Normalized manifest = SandboxSkillManifest.normalize(
            entries, "run-1", mapper
        );
        SandboxJobRow job = new SandboxJobRow();
        job.setWorkspaceKey("run-1");
        job.setSkillManifestJson(manifest.json());
        job.setSkillManifestHash(manifest.hash());
        return job;
    }

    private Map<String, Object> entry(
        long skillId,
        long versionId,
        String key,
        List<AgentSkillFile> files
    ) {
        return Map.of(
            "skillId", skillId,
            "versionId", versionId,
            "skillKey", key,
            "bundleHash", bundleHash(files)
        );
    }

    private AgentSkillFile file(String path, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        AgentSkillFile file = new AgentSkillFile();
        file.setPath(path);
        file.setFileKind("file");
        file.setContent(content);
        file.setContentEncoding("utf8");
        file.setContentHash(ContentHashing.sha256(bytes));
        file.setSizeBytes(bytes.length);
        file.setDelFlag("0");
        return file;
    }

    private String bundleHash(List<AgentSkillFile> files) {
        List<String> entries = files.stream()
            .map(file -> file.getPath() + "\n" + file.getFileKind() + "\n" + file.getContentHash())
            .sorted()
            .toList();
        return ContentHashing.sha256(String.join("\n", entries));
    }

    private List<String> tarPaths(byte[] archive) throws Exception {
        byte[] tar;
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(archive))) {
            tar = gzip.readAllBytes();
        }
        List<String> paths = new ArrayList<>();
        int offset = 0;
        while (offset + 512 <= tar.length && !zeroBlock(tar, offset)) {
            String name = text(tar, offset, 100);
            String prefix = text(tar, offset + 345, 155);
            paths.add(prefix.isEmpty() ? name : prefix + "/" + name);
            long size = Long.parseLong(text(tar, offset + 124, 12).strip(), 8);
            offset += 512 + (int) (((size + 511) / 512) * 512);
        }
        return paths;
    }

    private String text(byte[] bytes, int offset, int length) {
        int end = offset;
        while (end < offset + length && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, offset, end - offset, StandardCharsets.UTF_8);
    }

    private boolean zeroBlock(byte[] bytes, int offset) {
        for (int index = offset; index < offset + 512; index++) {
            if (bytes[index] != 0) {
                return false;
            }
        }
        return true;
    }
}
