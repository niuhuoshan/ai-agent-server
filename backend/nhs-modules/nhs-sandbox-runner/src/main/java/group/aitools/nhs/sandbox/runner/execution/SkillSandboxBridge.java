package group.aitools.nhs.sandbox.runner.execution;

import group.aitools.nhs.sandbox.runner.client.SandboxPlatformClient;
import group.aitools.nhs.sandbox.runner.client.SandboxPlatformClient.SkillBundle;
import group.aitools.nhs.sandbox.runner.client.SandboxProtocol.ClaimedJob;
import group.aitools.nhs.sandbox.runner.config.SandboxRunnerProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * 表示技能沙箱Bridge相关的领域对象。
 *
 * Fetches and validates a frozen Skill bundle for one Sandbox attempt.
 *
 * <p>The platform sends the bundle over the authenticated job endpoint. The extracted files are
 * kept in a job/attempt-specific staging directory and mounted over {@code /workspace/skills}
 * read-only. A missing or invalid bundle is an execution failure; it is never silently treated as
 * an ordinary workspace.</p>
 */
@Component
public class SkillSandboxBridge {

    private static final Pattern WORKSPACE_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern SKILL_KEY = Pattern.compile("[a-z][a-z0-9._-]{0,127}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final int MANIFEST_VERSION = 1;
    private static final int MAX_SKILLS = 128;
    private static final int MAX_FILES = 4096;
    private static final long MAX_ARCHIVE_BYTES = 128L * 1024 * 1024;
    private static final long MAX_EXTRACTED_BYTES = 128L * 1024 * 1024;
    private static final int TAR_BLOCK = 512;

    private final SandboxRunnerProperties properties;
    private final SandboxPlatformClient platformClient;
    private final JsonMapper jsonMapper;

    public SkillSandboxBridge(
        SandboxRunnerProperties properties,
        SandboxPlatformClient platformClient,
        JsonMapper jsonMapper
    ) {
        this.properties = properties;
        this.platformClient = platformClient;
        this.jsonMapper = jsonMapper;
    }

    /**
 * 处理{@code prepare}并返回对应结果。
 *
     * Returns null for ordinary jobs. For a Skill job, returns a mount only after the manifest,
     * response header, archive paths and every declared file bundle hash have been checked.
     */
    public PreparedMount prepare(ClaimedJob job) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (job == null) {
            throw unavailable("claimed job is missing");
        }
        if ((isBlank(job.skillManifestJson()) && isBlank(job.skillManifestHash()))
            || isEmptyManifest(job.skillManifestJson())) {
            return null;
        }
        Manifest manifest = parseManifest(job);
        if (platformClient == null) {
            throw unavailable("platform bundle client is not configured");
        }
        SkillBundle bundle;
        try {
            bundle = platformClient.downloadSkillBundle(job);
        } catch (RuntimeException failure) {
            throw unavailable("Skill bundle download failed");
        }
        if (!manifest.hash().equalsIgnoreCase(bundle.manifestHash())) {
            throw unavailable("platform bundle manifest hash does not match the claimed snapshot");
        }
        byte[] archive = bundle.bytes();
        if (archive.length > MAX_ARCHIVE_BYTES) {
            throw unavailable("Skill bundle exceeds the archive size limit");
        }

        Path stagingRoot = properties.getSkillStagingRoot().toAbsolutePath().normalize();
        rejectLink(stagingRoot, "Skill staging root");
        Path staging = stagingRoot.resolve(
            "job-" + positive(job.jobId(), "job id") + "-attempt-" + positiveInt(job.attemptNo(), "attempt")
        ).normalize();
        if (!staging.startsWith(stagingRoot) || Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
            throw unavailable("Skill staging path is invalid or already exists");
        }
        try {
            Files.createDirectories(staging);
            rejectLink(staging, "Skill staging path");
            ExtractionIndex extraction = extractTarGzip(archive, staging);
            Path skillsRoot = staging.resolve("skills").normalize();
            requireDirectory(skillsRoot, "Skill staging directory");
            verifyManifest(manifest, skillsRoot, extraction);
            makeReadableBySandbox(staging);
            return new PreparedMount(staging, skillsRoot, manifest.skillKeys());
        } catch (IOException | RuntimeException failure) {
            cleanup(new PreparedMount(staging, staging.resolve("skills"), manifest.skillKeys()));
            if (failure instanceof SkillBridgeException bridgeFailure) {
                throw bridgeFailure;
            }
            throw unavailable("Skill bundle staging failed");
        }
    }

    /**
     * 处理{@code cleanup}相关逻辑。
     *
     * @param mount {@code mount}参数
     */
    public void cleanup(PreparedMount mount) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (mount == null || mount.stagingRoot() == null) {
            return;
        }
        try {
            Path configuredRoot = properties.getSkillStagingRoot().toAbsolutePath().normalize();
            Path target = mount.stagingRoot().toAbsolutePath().normalize();
            if (!target.startsWith(configuredRoot) || target.equals(configuredRoot)) {
                return;
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                deleteTree(target);
            }
        } catch (IOException failure) {
            // A stale staging directory is safer than deleting outside the checked root. Surface
            // the problem on the next attempt through the collision check in prepare().
        }
    }

    /**
     * 处理{@code parseManifest}并返回对应结果。
     *
     * @param job 作业参数
     * @return 处理结果
     */
    private Manifest parseManifest(ClaimedJob job) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        String raw = job.skillManifestJson();
        String expectedHash = job.skillManifestHash();
        if (isBlank(raw) || isBlank(expectedHash) || !SHA256.matcher(expectedHash).matches()) {
            throw unavailable("Skill manifest or hash is missing");
        }
        String workspaceKey = requiredText(job.workspaceKey(), "workspace key");
        if (!WORKSPACE_KEY.matcher(workspaceKey).matches()) {
            throw unavailable("workspace key is invalid");
        }
        try {
            JsonNode root = jsonMapper.readTree(raw);
            String actualHash = sha256(raw.getBytes(StandardCharsets.UTF_8));
            String canonicalHash = sha256(canonicalJson(root).getBytes(StandardCharsets.UTF_8));
            if (!actualHash.equalsIgnoreCase(expectedHash)
                && !canonicalHash.equalsIgnoreCase(expectedHash)) {
                throw unavailable("Skill manifest hash does not match its content");
            }
            if (root == null || !root.isObject() || root.path("version").asInt(-1) != MANIFEST_VERSION) {
                throw unavailable("unsupported Skill manifest version");
            }
            if (!workspaceKey.equals(root.path("workspaceKey").asText(null))) {
                throw unavailable("Skill manifest workspace key mismatch");
            }
            JsonNode skills = root.get("skills");
            if (skills == null || !skills.isArray() || skills.isEmpty() || skills.size() > MAX_SKILLS) {
                throw unavailable("Skill manifest contains no valid skills");
            }
            List<SkillSpec> specs = new ArrayList<>();
            Set<String> keys = new HashSet<>();
            for (JsonNode item : skills) {
                if (item == null || !item.isObject()) {
                    throw unavailable("Skill manifest item is invalid");
                }
                String key = item.path("skillKey").asText(null);
                String bundleHash = item.path("fileBundleHash").asText(null);
                long versionId = item.path("versionId").asLong(-1);
                if (key == null || !SKILL_KEY.matcher(key).matches() || !keys.add(key)
                    || versionId <= 0 || bundleHash == null || !SHA256.matcher(bundleHash).matches()) {
                    throw unavailable("Skill manifest item is invalid");
                }
                specs.add(new SkillSpec(key, versionId, bundleHash.toLowerCase(Locale.ROOT)));
            }
            return new Manifest(expectedHash.toLowerCase(Locale.ROOT), specs);
        } catch (SkillBridgeException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw unavailable("Skill manifest JSON is invalid");
        }
    }

    /**
     * 校验{@code Manifest}，并在条件不满足时终止处理。
     *
     * @param manifest {@code manifest}参数
     * @param skillsRoot {@code skillsRoot}参数
     * @param extraction {@code extraction}参数
     * @throws IOException 当处理过程无法正常完成时抛出
     */
    private void verifyManifest(
        Manifest manifest,
        Path skillsRoot,
        ExtractionIndex extraction
    ) throws IOException {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        Set<String> expected = new HashSet<>();
        for (SkillSpec spec : manifest.skills()) {
            expected.add(spec.skillKey());
            Path skillRoot = skillsRoot.resolve(spec.skillKey()).normalize();
            if (!skillRoot.startsWith(skillsRoot) || !Files.isDirectory(skillRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw unavailable("Skill bundle omitted " + spec.skillKey());
            }
            rejectTreeLinks(skillRoot);
            if (!Files.isRegularFile(skillRoot.resolve("SKILL.md"), LinkOption.NOFOLLOW_LINKS)) {
                throw unavailable("Skill bundle omitted SKILL.md for " + spec.skillKey());
            }
            String actual = bundleHash(skillRoot, extraction.explicitDirectories());
            if (!actual.equalsIgnoreCase(spec.fileBundleHash())) {
                throw unavailable("Skill file bundle hash mismatch for " + spec.skillKey());
            }
        }
        try (var stream = Files.list(skillsRoot)) {
            for (Path child : stream.toList()) {
                if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)
                    || !expected.contains(child.getFileName().toString())) {
                    throw unavailable("Skill bundle contains an undeclared skill");
                }
            }
        }
    }

    /**
     * 处理{@code extractTarGzip}并返回对应结果。
     *
     * @param archive {@code archive}参数
     * @param staging {@code staging}参数
     * @return 处理结果
     * @throws IOException 当处理过程无法正常完成时抛出
     */
    private ExtractionIndex extractTarGzip(byte[] archive, Path staging) throws IOException {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        Set<String> paths = new HashSet<>();
        Set<String> explicitDirectories = new HashSet<>();
        long extractedBytes = 0;
        int files = 0;
        boolean ended = false;
        try (InputStream gzip = new GZIPInputStream(new ByteArrayInputStream(archive))) {
            byte[] header = new byte[TAR_BLOCK];
            while (readBlock(gzip, header)) {
                if (zeroBlock(header)) {
                    if (!readBlock(gzip, header) || !zeroBlock(header)) {
                        throw new IOException("tar end marker is truncated");
                    }
                    if (gzip.read() != -1) {
                        throw new IOException("tar archive contains trailing bytes");
                    }
                    ended = true;
                    break;
                }
                verifyTarChecksum(header);
                String name = tarString(header, 0, 100);
                String prefix = tarString(header, 345, 155);
                if (!prefix.isBlank()) {
                    name = prefix + "/" + name;
                }
                String pathText = safeArchivePath(name);
                if (!pathText.startsWith("skills/")) {
                    throw unavailable("Skill bundle path must stay under skills/");
                }
                if (!paths.add(pathText)) {
                    throw unavailable("Skill bundle contains duplicate path");
                }
                long size = tarOctal(header, 124, 12);
                if (size < 0 || size > MAX_EXTRACTED_BYTES || extractedBytes > MAX_EXTRACTED_BYTES - size) {
                    throw unavailable("Skill bundle contains an oversized entry");
                }
                char type = (char) (header[156] & 0xff);
                Path target = staging.resolve(pathText).normalize();
                if (!target.startsWith(staging)) {
                    throw unavailable("Skill bundle path escapes staging");
                }
                if (type == '5') {
                    if (size != 0) throw unavailable("Skill directory entry has content");
                    Files.createDirectories(target);
                    explicitDirectories.add(pathText.endsWith("/")
                        ? pathText.substring(0, pathText.length() - 1) : pathText);
                } else if (type == 0 || type == '0' || type == '7') {
                    if (++files > MAX_FILES) throw unavailable("Skill bundle contains too many files");
                    Path parent = target.getParent();
                    if (parent == null) throw unavailable("Skill file has no parent");
                    Files.createDirectories(parent);
                    try (OutputStream output = Files.newOutputStream(
                        target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE
                    )) {
                        copyExactly(gzip, output, size);
                    }
                } else {
                    throw unavailable("Skill bundle contains an unsupported tar entry type");
                }
                extractedBytes += size;
                long padding = (TAR_BLOCK - (size % TAR_BLOCK)) % TAR_BLOCK;
                skipExactly(gzip, padding);
            }
            if (!ended) {
                throw new IOException("tar archive has no end marker");
            }
            return new ExtractionIndex(Set.copyOf(explicitDirectories));
        } catch (SkillBridgeException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new IOException("Invalid Skill tar.gz archive", failure);
        }
    }

    /**
     * 处理{@code bundleHash}并返回对应结果。
     *
     * @param skillRoot 技能Root参数
     * @param explicitDirectories {@code explicitDirectories}参数
     * @return 处理结果
     * @throws IOException 当处理过程无法正常完成时抛出
     */
    private String bundleHash(Path skillRoot, Set<String> explicitDirectories) throws IOException {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        List<String> entries = new ArrayList<>();
        try (var stream = Files.walk(skillRoot)) {
            for (Path path : stream.sorted().toList()) {
                if (path.equals(skillRoot)) continue;
                Path relative = skillRoot.relativize(path);
                String name = relative.toString().replace(java.io.File.separatorChar, '/');
                if (name.equals(".agent-dependencies") || name.startsWith(".agent-dependencies/")) {
                    continue;
                }
                if (Files.isSymbolicLink(path)) {
                    throw unavailable("Skill bundle contains a symbolic link");
                }
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    String archivePath = "skills/" + skillRoot.getFileName() + "/" + name;
                    if (explicitDirectories.contains(archivePath)) {
                        entries.add(name + "\ndirectory\n" + sha256(new byte[0]));
                    }
                } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    entries.add(name + "\nfile\n" + sha256(Files.readAllBytes(path)));
                } else {
                    throw unavailable("Skill bundle contains an unsupported filesystem entry");
                }
            }
        }
        entries.sort(Comparator.naturalOrder());
        return sha256(String.join("\n", entries).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 处理{@code rejectTreeLinks}相关逻辑。
     *
     * @param root {@code root}参数
     * @throws IOException 当处理过程无法正常完成时抛出
     */
    private void rejectTreeLinks(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            for (Path path : stream.toList()) {
                if (Files.isSymbolicLink(path)) {
                    throw unavailable("Skill bundle contains a symbolic link");
                }
            }
        }
    }

    /**
     * 处理makeReadableBy沙箱相关逻辑。
     *
     * @param root {@code root}参数
     * @throws IOException 当处理过程无法正常完成时抛出
     */
    private void makeReadableBySandbox(Path root) throws IOException {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        try (var stream = Files.walk(root)) {
            for (Path path : stream.toList()) {
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.setPosixFilePermissions(path, Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                        PosixFilePermission.GROUP_READ,
                        PosixFilePermission.GROUP_EXECUTE,
                        PosixFilePermission.OTHERS_READ,
                        PosixFilePermission.OTHERS_EXECUTE
                    ));
                } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.setPosixFilePermissions(path, Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.GROUP_READ,
                        PosixFilePermission.OTHERS_READ
                    ));
                }
            }
        } catch (UnsupportedOperationException ignored) {
            // Container deployments are POSIX; other filesystems rely on their mount ACLs.
        }
    }

    /**
     * 校验目录，并在条件不满足时终止处理。
     *
     * @param path {@code path}参数
     * @param label {@code label}参数
     * @throws IOException 当处理过程无法正常完成时抛出
     */
    private void requireDirectory(Path path, String label) throws IOException {
        rejectLink(path, label);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw unavailable(label + " is missing");
        }
    }

    /**
     * 删除{@code Tree}。
     *
     * @param root {@code root}参数
     * @throws IOException 当处理过程无法正常完成时抛出
     */
    private void deleteTree(Path root) throws IOException {
        Files.walkFileTree(root, EnumSet.noneOf(java.nio.file.FileVisitOption.class), Integer.MAX_VALUE,
            new java.nio.file.SimpleFileVisitor<>() {
                /**
                 * 处理visit文件并返回对应结果。
                 *
                 * @param file 文件参数
                 * @param attrs {@code attrs}参数
                 * @return 处理结果
                 * @throws IOException 当处理过程无法正常完成时抛出
                 */
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                /**
                 * 处理postVisit目录并返回对应结果。
                 *
                 * @param directory 目录参数
                 * @param exception {@code exception}参数
                 * @return 处理结果
                 * @throws IOException 当处理过程无法正常完成时抛出
                 */
                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException exception)
                    throws IOException {
                    if (exception != null) throw exception;
                    Files.deleteIfExists(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
    }

    /**
     * 处理{@code copyExactly}相关逻辑。
     *
     * @param input {@code input}参数
     * @param output {@code output}参数
     * @param size 数量上限
     * @throws IOException 当处理过程无法正常完成时抛出
     */
    private void copyExactly(InputStream input, OutputStream output, long size) throws IOException {
        byte[] buffer = new byte[8192];
        long remaining = size;
        while (remaining > 0) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) throw new IOException("tar entry is truncated");
            output.write(buffer, 0, read);
            remaining -= read;
        }
    }

    /**
     * 处理{@code skipExactly}相关逻辑。
     *
     * @param input {@code input}参数
     * @param size 数量上限
     * @throws IOException 当处理过程无法正常完成时抛出
     */
    private void skipExactly(InputStream input, long size) throws IOException {
        long remaining = size;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
                continue;
            }
            if (input.read() < 0) throw new IOException("tar padding is truncated");
            remaining--;
        }
    }

    /**
     * 处理{@code readBlock}并返回对应结果。
     *
     * @param input {@code input}参数
     * @param target {@code target}参数
     * @return 判断结果，{@code true} 表示条件成立
     * @throws IOException 当处理过程无法正常完成时抛出
     */
    private boolean readBlock(InputStream input, byte[] target) throws IOException {
        int offset = 0;
        while (offset < target.length) {
            int read = input.read(target, offset, target.length - offset);
            if (read < 0) return offset == 0 ? false : throwTruncated();
            if (read == 0) continue;
            offset += read;
        }
        return true;
    }

    /**
     * 处理{@code throwTruncated}并返回对应结果。
     *
     * @return 判断结果，{@code true} 表示条件成立
     * @throws IOException 当处理过程无法正常完成时抛出
     */
    private boolean throwTruncated() throws IOException {
        throw new IOException("tar header is truncated");
    }

    /**
     * 处理{@code zeroBlock}并返回对应结果。
     *
     * @param block {@code block}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean zeroBlock(byte[] block) {
        for (byte value : block) if (value != 0) return false;
        return true;
    }

    /**
     * 处理{@code tarString}并返回对应结果。
     *
     * @param block {@code block}参数
     * @param offset 起始位置或序号
     * @param length {@code length}参数
     * @return 处理结果
     */
    private String tarString(byte[] block, int offset, int length) {
        int end = offset;
        while (end < offset + length && block[end] != 0) end++;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(block, offset, end - offset))
                .toString();
        } catch (CharacterCodingException failure) {
            throw unavailable("tar path encoding is invalid");
        }
    }

    /**
     * 处理{@code tarOctal}并返回对应结果。
     *
     * @param block {@code block}参数
     * @param offset 起始位置或序号
     * @param length {@code length}参数
     * @return 处理结果
     */
    private long tarOctal(byte[] block, int offset, int length) {
        String value = tarString(block, offset, length).replace("\0", "").strip();
        if (value.isEmpty()) return 0;
        try {
            return Long.parseLong(value, 8);
        } catch (NumberFormatException failure) {
            throw unavailable("tar entry size is invalid");
        }
    }

    /**
     * 校验{@code TarChecksum}，并在条件不满足时终止处理。
     *
     * @param header {@code header}参数
     */
    private void verifyTarChecksum(byte[] header) {
        long expected = tarOctal(header, 148, 8);
        long actual = 0;
        for (int index = 0; index < header.length; index++) {
            actual += index >= 148 && index < 156 ? (byte) ' ' : (header[index] & 0xff);
        }
        if (actual != expected) {
            throw unavailable("tar header checksum is invalid");
        }
    }

    /**
     * 处理{@code safeArchivePath}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String safeArchivePath(String value) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0 || value.indexOf('\\') >= 0) {
            throw unavailable("Skill bundle path is invalid");
        }
        Path path = Path.of(value).normalize();
        if (path.isAbsolute() || path.startsWith("..")) {
            throw unavailable("Skill bundle path escapes staging");
        }
        for (Path part : path) {
            if (part.toString().isBlank() || ".".equals(part.toString()) || "..".equals(part.toString())) {
                throw unavailable("Skill bundle path contains an illegal segment");
            }
        }
        return path.toString().replace(java.io.File.separatorChar, '/');
    }

    /**
     * 处理{@code rejectLink}相关逻辑。
     *
     * @param path {@code path}参数
     * @param label {@code label}参数
     */
    private void rejectLink(Path path, String label) {
        if (Files.isSymbolicLink(path)) {
            throw unavailable(label + " cannot be a symbolic link");
        }
    }

    /**
     * 处理{@code positive}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private long positive(Long value, String label) {
        if (value == null || value <= 0) throw unavailable(label + " is invalid");
        return value;
    }

    /**
     * 处理{@code positiveInt}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private int positiveInt(Integer value, String label) {
        if (value == null || value <= 0) throw unavailable(label + " is invalid");
        return value;
    }

    /**
     * 校验{@code dText}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String requiredText(String value, String label) {
        if (value == null || value.isBlank()) throw unavailable(label + " is missing");
        return value.strip();
    }

    /**
     * 判断{@code Blank}是否满足要求。
     *
     * @param value {@code value}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 判断{@code EmptyManifest}是否满足要求。
     *
     * @param value {@code value}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isEmptyManifest(String value) {
        if (value == null) return false;
        String stripped = value.strip();
        if ("[]".equals(stripped) || "{}".equals(stripped)) return true;
        try {
            JsonNode root = jsonMapper.readTree(stripped);
            JsonNode skills = root == null ? null : root.get("skills");
            return (root != null && root.isArray() && root.isEmpty())
                || (root != null && root.isObject() && skills != null
                    && skills.isArray() && skills.isEmpty());
        } catch (RuntimeException failure) {
            return false;
        }
    }

    /**
     * 处理{@code sha256}并返回对应结果。
     *
     * @param bytes {@code bytes}参数
     * @return 处理结果
     */
    private String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes)
            );
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    /**
 * 判断{@code onicalJson}是否满足要求。
 * PostgreSQL JSONB may normalize object key order; hash both raw and sorted canonical JSON. */
    private String canonicalJson(JsonNode node) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (node == null || node.isNull()) return "null";
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            names.addAll(node.propertyNames());
            names.sort(Comparator.naturalOrder());
            StringBuilder result = new StringBuilder("{");
            for (int index = 0; index < names.size(); index++) {
                if (index > 0) result.append(',');
                String name = names.get(index);
                try {
                    result.append(jsonMapper.writeValueAsString(name));
                } catch (RuntimeException failure) {
                    throw unavailable("Skill manifest key is invalid");
                }
                result.append(':').append(canonicalJson(node.get(name)));
            }
            return result.append('}').toString();
        }
        if (node.isArray()) {
            StringBuilder result = new StringBuilder("[");
            for (int index = 0; index < node.size(); index++) {
                if (index > 0) result.append(',');
                result.append(canonicalJson(node.get(index)));
            }
            return result.append(']').toString();
        }
        try {
            return jsonMapper.writeValueAsString(node);
        } catch (RuntimeException failure) {
            throw unavailable("Skill manifest value is invalid");
        }
    }

    /**
     * 处理{@code unavailable}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private SkillBridgeException unavailable(String message) {
        return new SkillBridgeException("SKILL_BRIDGE_UNAVAILABLE", message);
    }

    /**
     * 封装{@code PreparedMount}相关的不可变数据。
     */
    public record PreparedMount(Path stagingRoot, Path skillsRoot, List<String> skillKeys) {
        /**
         * 创建 {@code PreparedMount} 实例并初始化所需依赖。
         *
         * @param stagingRoot {@code stagingRoot}参数
         * @param skillsRoot {@code skillsRoot}参数
         * @param skillKeys 技能Keys参数
         */
        public PreparedMount {
            skillKeys = skillKeys == null ? List.of() : List.copyOf(skillKeys);
        }
    }

    /**
     * 封装{@code Manifest}相关的不可变数据。
     */
    private record Manifest(String hash, List<SkillSpec> skills) {
        /**
         * 处理技能Keys并返回对应结果。
         *
         * @return 符合条件的数据集合
         */
        List<String> skillKeys() {
            return skills.stream().map(SkillSpec::skillKey).toList();
        }
    }

    /**
     * 封装技能Spec相关的不可变数据。
     */
    private record SkillSpec(String skillKey, long versionId, String fileBundleHash) {
    }

    /**
     * 封装{@code ExtractionIndex}相关的不可变数据。
     */
    private record ExtractionIndex(Set<String> explicitDirectories) {
    }

    /**
     * 表示技能Bridge处理过程中发生的业务异常。
     */
    public static final class SkillBridgeException extends RuntimeException {
        private final String code;

        /**
         * 创建 {@code SkillBridgeException} 实例并初始化所需依赖。
         *
         * @param code {@code code}参数
         * @param message 待处理内容
         */
        public SkillBridgeException(String code, String message) {
            super(message);
            this.code = code;
        }

        /**
         * 处理{@code code}并返回对应结果。
         *
         * @return 处理结果
         */
        public String code() {
            return code;
        }
    }
}
