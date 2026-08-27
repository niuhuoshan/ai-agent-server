package group.aitools.nhs.sandbox.runner.execution;

import group.aitools.nhs.sandbox.runner.client.SandboxPlatformClient;
import group.aitools.nhs.sandbox.runner.client.SandboxProtocol.ClaimedJob;
import group.aitools.nhs.sandbox.runner.config.SandboxRunnerProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class SkillSandboxBridgeTest {

    @Test
    void validatesManifestAndStagesTarGzForOneAttempt() throws Exception {
        Path root = Files.createTempDirectory("sandbox-bridge-");
        SandboxRunnerProperties properties = properties(root);
        SandboxPlatformClient client = mock(SandboxPlatformClient.class);
        String content = "# frozen\n";
        String fileHash = sha256(content.getBytes(StandardCharsets.UTF_8));
        String bundleHash = sha256(("SKILL.md\nfile\n" + fileHash).getBytes(StandardCharsets.UTF_8));
        String manifest = manifest("run-1", bundleHash);
        byte[] archive = tarGz(new Entry("skills/reviewer/SKILL.md", content.getBytes(StandardCharsets.UTF_8), false));
        when(client.downloadSkillBundle(any())).thenReturn(
            new SandboxPlatformClient.SkillBundle(archive, sha256(manifest.getBytes(StandardCharsets.UTF_8)))
        );

        SkillSandboxBridge bridge = new SkillSandboxBridge(properties, client, JsonMapper.builder().build());
        SkillSandboxBridge.PreparedMount mount = bridge.prepare(job(manifest, sha256(manifest.getBytes(StandardCharsets.UTF_8))));
        assertThat(mount.skillKeys()).containsExactly("reviewer");
        assertThat(Files.readString(mount.skillsRoot().resolve("reviewer/SKILL.md"))).isEqualTo(content);

        bridge.cleanup(mount);
        assertThat(Files.exists(mount.stagingRoot())).isFalse();
    }

    @Test
    void rejectsManifestHashMismatchBeforeDownloading() throws Exception {
        Path root = Files.createTempDirectory("sandbox-bridge-");
        SandboxPlatformClient client = mock(SandboxPlatformClient.class);
        SandboxRunnerProperties properties = properties(root);
        SkillSandboxBridge bridge = new SkillSandboxBridge(properties, client, JsonMapper.builder().build());
        String manifest = manifest("run-1", "a".repeat(64));

        assertThatThrownBy(() -> bridge.prepare(job(manifest, "b".repeat(64))))
            .isInstanceOf(SkillSandboxBridge.SkillBridgeException.class)
            .hasMessageContaining("manifest hash");
    }

    @Test
    void rejectsTraversalAndUnsupportedTarEntries() throws Exception {
        Path root = Files.createTempDirectory("sandbox-bridge-");
        SandboxPlatformClient client = mock(SandboxPlatformClient.class);
        SandboxRunnerProperties properties = properties(root);
        String fileHash = sha256("# frozen\n".getBytes(StandardCharsets.UTF_8));
        String bundleHash = sha256(("SKILL.md\nfile\n" + fileHash).getBytes(StandardCharsets.UTF_8));
        String manifest = manifest("run-1", bundleHash);
        byte[] archive = tarGz(new Entry("skills/reviewer/../../escape", new byte[] {1}, false));
        when(client.downloadSkillBundle(any())).thenReturn(
            new SandboxPlatformClient.SkillBundle(archive, sha256(manifest.getBytes(StandardCharsets.UTF_8)))
        );
        SkillSandboxBridge bridge = new SkillSandboxBridge(properties, client, JsonMapper.builder().build());

        assertThatThrownBy(() -> bridge.prepare(job(manifest, sha256(manifest.getBytes(StandardCharsets.UTF_8)))))
            .isInstanceOf(SkillSandboxBridge.SkillBridgeException.class)
            .hasMessageContaining("path");
    }

    @Test
    void decodesUtf8TarPathsWithoutChangingTheFrozenBundleHash() throws Exception {
        Path root = Files.createTempDirectory("sandbox-bridge-");
        SandboxPlatformClient client = mock(SandboxPlatformClient.class);
        SandboxRunnerProperties properties = properties(root);
        byte[] skill = "# frozen\n".getBytes(StandardCharsets.UTF_8);
        byte[] note = "说明\n".getBytes(StandardCharsets.UTF_8);
        String skillHash = sha256(skill);
        String noteHash = sha256(note);
        String bundleHash = sha256((
            "SKILL.md\nfile\n" + skillHash + "\n说明.txt\nfile\n" + noteHash
        ).getBytes(StandardCharsets.UTF_8));
        String manifest = manifest("run-1", bundleHash);
        byte[] archive = tarGz(
            new Entry("skills/reviewer/SKILL.md", skill, false),
            new Entry("skills/reviewer/说明.txt", note, false)
        );
        when(client.downloadSkillBundle(any())).thenReturn(
            new SandboxPlatformClient.SkillBundle(archive, sha256(manifest.getBytes(StandardCharsets.UTF_8)))
        );

        SkillSandboxBridge bridge = new SkillSandboxBridge(properties, client, JsonMapper.builder().build());
        SkillSandboxBridge.PreparedMount mount = bridge.prepare(
            job(manifest, sha256(manifest.getBytes(StandardCharsets.UTF_8)))
        );

        assertThat(Files.readString(mount.skillsRoot().resolve("reviewer/说明.txt")))
            .isEqualTo("说明\n");
        bridge.cleanup(mount);
    }

    @Test
    void rejectsTrailingTarBytesAfterTheEndMarker() throws Exception {
        Path root = Files.createTempDirectory("sandbox-bridge-");
        SandboxPlatformClient client = mock(SandboxPlatformClient.class);
        SandboxRunnerProperties properties = properties(root);
        byte[] content = "# frozen\n".getBytes(StandardCharsets.UTF_8);
        String fileHash = sha256(content);
        String bundleHash = sha256(("SKILL.md\nfile\n" + fileHash).getBytes(StandardCharsets.UTF_8));
        String manifest = manifest("run-1", bundleHash);
        byte[] archive = tarGzWithTrailing(
            new byte[] {1}, new Entry("skills/reviewer/SKILL.md", content, false)
        );
        when(client.downloadSkillBundle(any())).thenReturn(
            new SandboxPlatformClient.SkillBundle(archive, sha256(manifest.getBytes(StandardCharsets.UTF_8)))
        );
        SkillSandboxBridge bridge = new SkillSandboxBridge(properties, client, JsonMapper.builder().build());

        assertThatThrownBy(() -> bridge.prepare(
            job(manifest, sha256(manifest.getBytes(StandardCharsets.UTF_8)))
        )).isInstanceOf(SkillSandboxBridge.SkillBridgeException.class)
            .hasMessageContaining("staging");
    }

    @Test
    void treatsLegacyEmptyManifestAsAnOrdinaryJob() throws Exception {
        Path root = Files.createTempDirectory("sandbox-bridge-");
        SandboxPlatformClient client = mock(SandboxPlatformClient.class);
        SkillSandboxBridge bridge = new SkillSandboxBridge(properties(root), client, JsonMapper.builder().build());
        assertThatCode(() -> bridge.prepare(job("[]", null))).doesNotThrowAnyException();
        assertThat(bridge.prepare(job("[]", null))).isNull();
    }

    private SandboxRunnerProperties properties(Path root) {
        SandboxRunnerProperties properties = new SandboxRunnerProperties();
        properties.setWorkspaceRoot(root.resolve("workspaces"));
        properties.setSkillStagingRoot(root.resolve("staging"));
        return properties;
    }

    private ClaimedJob job(String manifest, String hash) {
        return new ClaimedJob(
            17L, 1L, 2L, 3L, 4L, "trace", "token", "python-3.11",
            List.of("python", "-c", "print(1)"), ".", "read_write", "none", List.of(),
            60, 256, 1000, 64, 1024, null, 1,
            "task_tool", 9L, null, null, null, "run-1", manifest, hash
        );
    }

    private String manifest(String workspaceKey, String bundleHash) {
        return "{\"version\":1,\"workspaceKey\":\"" + workspaceKey
            + "\",\"skills\":[{\"skillId\":10,\"versionId\":11,\"skillKey\":\"reviewer\",\"fileBundleHash\":\""
            + bundleHash + "\"}]}";
    }

    private byte[] tarGz(Entry... entries) throws IOException {
        return tarGzWithTrailing(new byte[0], entries);
    }

    private byte[] tarGzWithTrailing(byte[] trailing, Entry... entries) throws IOException {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        for (Entry entry : entries) {
            byte[] header = new byte[512];
            writeUtf8(header, 0, 100, entry.name());
            writeAscii(header, 100, 8, "0000777\0");
            writeAscii(header, 108, 8, "0000000\0");
            writeAscii(header, 116, 8, "0000000\0");
            writeAscii(header, 124, 12, String.format("%011o\0", entry.data().length));
            writeAscii(header, 136, 12, "00000000000\0");
            for (int i = 148; i < 156; i++) header[i] = (byte) ' ';
            header[156] = (byte) (entry.directory() ? '5' : '0');
            writeAscii(header, 257, 6, "ustar\0");
            long checksum = 0;
            for (byte value : header) checksum += value & 0xff;
            writeAscii(header, 148, 8, String.format("%06o\0 ", checksum));
            tar.write(header);
            tar.write(entry.data());
            int padding = (int) ((512 - (entry.data().length % 512)) % 512);
            tar.write(new byte[padding]);
        }
        tar.write(new byte[1024]);
        tar.write(trailing);
        ByteArrayOutputStream gzip = new ByteArrayOutputStream();
        try (GZIPOutputStream output = new GZIPOutputStream(gzip)) {
            output.write(tar.toByteArray());
        }
        return gzip.toByteArray();
    }

    private void writeAscii(byte[] target, int offset, int length, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, target, offset, Math.min(bytes.length, length));
    }

    private void writeUtf8(byte[] target, int offset, int length, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, target, offset, Math.min(bytes.length, length));
    }

    private String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record Entry(String name, byte[] data, boolean directory) {
    }
}
