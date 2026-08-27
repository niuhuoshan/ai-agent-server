package group.aitools.nhs.platform.nhs.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 负责Generated文件相关的业务编排与领域规则处理。
 * Publishes generated files behind short-lived bearer capability links. */
@Service
public class GeneratedFileService {

    public static final Duration DEFAULT_TTL = Duration.ofHours(24);
    static final Duration STAGING_MAX_AGE = Duration.ofHours(1);

    private static final Pattern ARTIFACT_ID = Pattern.compile("[a-f0-9]{32}");
    private static final TypeReference<Map<String, Object>> MANIFEST_TYPE = new TypeReference<>() {
    };
    private static final Map<String, String> OFFICE_MIME_TYPES = Map.of(
        ".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        ".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    );

    private final JsonMapper jsonMapper;
    private final Path configuredRoot;
    private final Clock clock;
    private final SecureRandom secureRandom;

    /**
     * 创建 {@code GeneratedFileService} 实例并初始化所需依赖。
     *
     * @param jsonMapper {@code jsonMapper}参数
     * @param storageRoot 存储Root参数
     */
    @Autowired
    public GeneratedFileService(
        JsonMapper jsonMapper,
        @Value("${agent.platform.generated-files.storage-root:./data/agent-generated-files}") String storageRoot
    ) {
        this(jsonMapper, Path.of(storageRoot), Clock.systemUTC(), new SecureRandom());
    }

    /**
     * 创建 {@code GeneratedFileService} 实例并初始化所需依赖。
     *
     * @param jsonMapper {@code jsonMapper}参数
     * @param storageRoot 存储Root参数
     * @param clock {@code clock}参数
     * @param secureRandom {@code secureRandom}参数
     */
    GeneratedFileService(JsonMapper jsonMapper, Path storageRoot, Clock clock, SecureRandom secureRandom) {
        this.jsonMapper = jsonMapper;
        this.configuredRoot = storageRoot.toAbsolutePath().normalize();
        this.clock = clock;
        this.secureRandom = secureRandom;
    }

    /**
     * 处理{@code publish}并返回对应结果。
     *
     * @param sourcePath 数据源Path参数
     * @param fileName 名称
     * @return 处理结果
     */
    public PublishedFile publish(Path sourcePath, String fileName) {
        return publish(sourcePath, fileName, DEFAULT_TTL);
    }

    /**
     * 处理{@code publish}并返回对应结果。
     *
     * @param sourcePath 数据源Path参数
     * @param fileName 名称
     * @param ttl {@code ttl}参数
     * @return 处理结果
     */
    public PublishedFile publish(Path sourcePath, String fileName, Duration ttl) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (sourcePath == null || ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("生成文件和有效期不能为空");
        }
        Path source = sourcePath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("待发布文件不存在");
        }
        String displayName = displayName(fileName);
        Path root = root();
        purgeExpired(root);

        String artifactId = randomHex(16);
        String token = randomToken();
        Instant expiresAt = clock.instant().plus(ttl);
        Path artifactDirectory = root.resolve(artifactId);
        Path stagingDirectory = root.resolve(".tmp-" + artifactId + "-" + randomHex(8));
        try {
            Files.createDirectory(stagingDirectory);
            restrictDirectory(stagingDirectory);
            Path destination = stagingDirectory.resolve(displayName);
            Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
            long size = Files.size(destination);
            String mimeType = mimeType(displayName, destination);

            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("artifact_id", artifactId);
            manifest.put("filename", displayName);
            manifest.put("mime_type", mimeType);
            manifest.put("size", size);
            manifest.put("token_hash", tokenHash(token));
            manifest.put("expires_at", expiresAt.toString());
            writeManifest(stagingDirectory, manifest);
            moveDirectory(stagingDirectory, artifactDirectory);
            return new PublishedFile(artifactId, token, displayName, mimeType, size, expiresAt);
        } catch (IOException | RuntimeException exception) {
            removeTree(stagingDirectory);
            throw new IllegalStateException("生成文件发布失败", exception);
        }
    }

    /**
     * 获取{@code resolve}。
     *
     * @param artifactId 资源标识
     * @param token 令牌参数
     * @return 可能为空的处理结果
     */
    public Optional<GeneratedFile> resolve(String artifactId, String token) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (artifactId == null || !ARTIFACT_ID.matcher(artifactId).matches()
            || token == null || token.isBlank() || token.length() > 256) {
            return Optional.empty();
        }
        Path root = root();
        Path artifactDirectory = root.resolve(artifactId);
        if (!Files.isDirectory(artifactDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            Path manifestPath = artifactDirectory.resolve("manifest.json");
            if (!Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            Map<String, Object> manifest = jsonMapper.readValue(Files.readAllBytes(manifestPath), MANIFEST_TYPE);
            if (!artifactId.equals(text(manifest, "artifact_id"))) {
                return Optional.empty();
            }
            Instant expiresAt = Instant.parse(text(manifest, "expires_at"));
            if (!expiresAt.isAfter(clock.instant())) {
                removeTree(artifactDirectory);
                return Optional.empty();
            }
            byte[] expectedHash = text(manifest, "token_hash").getBytes(StandardCharsets.US_ASCII);
            byte[] actualHash = tokenHash(token).getBytes(StandardCharsets.US_ASCII);
            if (!MessageDigest.isEqual(expectedHash, actualHash)) {
                return Optional.empty();
            }

            String fileName = displayName(text(manifest, "filename"));
            if (!fileName.equals(text(manifest, "filename"))) {
                return Optional.empty();
            }
            Path file = artifactDirectory.resolve(fileName).normalize();
            if (!file.getParent().equals(artifactDirectory)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            long size = number(manifest, "size");
            if (size < 0 || Files.size(file) != size) {
                return Optional.empty();
            }
            String mimeType = text(manifest, "mime_type");
            if (mimeType.length() > 255 || mimeType.indexOf('\r') >= 0 || mimeType.indexOf('\n') >= 0) {
                return Optional.empty();
            }
            return Optional.of(new GeneratedFile(
                artifactId, file, fileName, mimeType, size, expiresAt
            ));
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    /**
     * 处理{@code purgeExpired}相关逻辑。
     */
    public void purgeExpired() {
        purgeExpired(root());
    }

    /**
     * 处理{@code purgeExpired}相关逻辑。
     *
     * @param root {@code root}参数
     */
    private void purgeExpired(Path root) {
        Instant stagingCutoff = clock.instant().minus(STAGING_MAX_AGE);
        try (Stream<Path> entries = Files.list(root)) {
            entries.forEach(entry -> {
                if (entry.getFileName().toString().startsWith(".tmp-")) {
                    purgeExpiredStaging(entry, stagingCutoff);
                    return;
                }
                if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    removeTree(entry);
                    return;
                }
                try {
                    Path manifestPath = entry.resolve("manifest.json");
                    Map<String, Object> manifest = jsonMapper.readValue(
                        Files.readAllBytes(manifestPath), MANIFEST_TYPE
                    );
                    Instant expiresAt = Instant.parse(text(manifest, "expires_at"));
                    if (!expiresAt.isAfter(clock.instant())) {
                        removeTree(entry);
                    }
                } catch (IOException | RuntimeException exception) {
                    removeTree(entry);
                }
            });
        } catch (IOException exception) {
            throw new IllegalStateException("生成文件目录无法读取", exception);
        }
    }

    /**
     * 处理{@code purgeExpiredStaging}相关逻辑。
     *
     * @param entry {@code entry}参数
     * @param cutoff {@code cutoff}参数
     */
    private void purgeExpiredStaging(Path entry, Instant cutoff) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS
            );
            if ((!attributes.isDirectory() && !attributes.isRegularFile())
                || !attributes.lastModifiedTime().toInstant().isBefore(cutoff)) {
                return;
            }
            removeTree(entry);
        } catch (IOException | RuntimeException ignored) {
            // Unknown metadata must never turn into an unsafe deletion; cleanup remains best effort.
        }
    }

    /**
     * 处理{@code root}并返回对应结果。
     *
     * @return 处理结果
     */
    private Path root() {
        try {
            Files.createDirectories(configuredRoot);
            return configuredRoot.toRealPath();
        } catch (IOException exception) {
            throw new IllegalStateException("生成文件目录无法初始化", exception);
        }
    }

    /**
     * 处理{@code writeManifest}相关逻辑。
     *
     * @param artifactDirectory 制品目录参数
     * @param manifest {@code manifest}参数
     * @throws IOException 当处理过程无法正常完成时抛出
     */
    private void writeManifest(Path artifactDirectory, Map<String, Object> manifest) throws IOException {
        Path temporary = artifactDirectory.resolve("manifest.json.tmp");
        Path target = artifactDirectory.resolve("manifest.json");
        Files.write(temporary, jsonMapper.writeValueAsBytes(manifest));
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, target);
        }
    }

    /**
     * 处理move目录相关逻辑。
     *
     * @param source 数据源参数
     * @param target {@code target}参数
     * @throws IOException 当处理过程无法正常完成时抛出
     */
    private void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    /**
     * 处理{@code displayName}并返回对应结果。
     *
     * @param rawName 名称
     * @return 处理结果
     */
    private String displayName(String rawName) {
        String normalized = rawName == null ? "" : rawName.strip().replace('\\', '/');
        int separator = normalized.lastIndexOf('/');
        String value = separator >= 0 ? normalized.substring(separator + 1) : normalized;
        if (value.isBlank() || value.equals(".") || value.equals("..") || value.length() > 255
            || value.indexOf('\0') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
            || value.chars().anyMatch(character -> character < 32)) {
            throw new IllegalArgumentException("生成文件名无效");
        }
        return value;
    }

    /**
     * 处理{@code mimeType}并返回对应结果。
     *
     * @param fileName 名称
     * @param file 文件参数
     * @return 处理结果
     * @throws IOException 当处理过程无法正常完成时抛出
     */
    private String mimeType(String fileName, Path file) throws IOException {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : OFFICE_MIME_TYPES.entrySet()) {
            if (lower.endsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        String detected = Files.probeContentType(file);
        return detected == null || detected.isBlank() ? "application/octet-stream" : detected;
    }

    /**
     * 处理{@code randomHex}并返回对应结果。
     *
     * @param byteCount {@code byteCount}参数
     * @return 处理结果
     */
    private String randomHex(int byteCount) {
        byte[] bytes = new byte[byteCount];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * 处理random令牌并返回对应结果。
     *
     * @return 处理结果
     */
    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 将输入数据转换为{@code kenHash}。
     *
     * @param token 令牌参数
     * @return 处理结果
     */
    private String tokenHash(String token) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))
            );
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256不可用", exception);
        }
    }

    /**
     * 处理{@code text}并返回对应结果。
     *
     * @param manifest {@code manifest}参数
     * @param key {@code key}参数
     * @return 处理结果
     */
    private String text(Map<String, Object> manifest, String key) {
        Object value = manifest == null ? null : manifest.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("生成文件清单字段无效: " + key);
        }
        return text;
    }

    /**
     * 处理{@code number}并返回对应结果。
     *
     * @param manifest {@code manifest}参数
     * @param key {@code key}参数
     * @return 处理结果
     */
    private long number(Map<String, Object> manifest, String key) {
        Object value = manifest == null ? null : manifest.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("生成文件清单字段无效: " + key);
        }
        return number.longValue();
    }

    /**
     * 处理restrict目录相关逻辑。
     *
     * @param directory 目录参数
     */
    private void restrictDirectory(Path directory) {
        try {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystems rely on the process account's directory ACL.
        }
    }

    /**
     * 删除{@code Tree}。
     *
     * @param target {@code target}参数
     */
    private void removeTree(Path target) {
        Path safeRoot;
        try {
            safeRoot = configuredRoot.toRealPath();
        } catch (IOException exception) {
            return;
        }
        if (target == null || !target.toAbsolutePath().normalize().startsWith(safeRoot)) {
            return;
        }
        try {
            Files.walkFileTree(target, new SimpleFileVisitor<>() {
                /**
                 * 处理visit文件并返回对应结果。
                 *
                 * @param file 文件参数
                 * @param attributes {@code attributes}参数
                 * @return 处理结果
                 * @throws IOException 当处理过程无法正常完成时抛出
                 */
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
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
                public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                    Files.deleteIfExists(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // Expired-file cleanup is best effort and never exposes the file.
        }
    }

    /**
     * 封装Published文件相关的不可变数据。
     */
    public record PublishedFile(
        String artifactId,
        String token,
        String fileName,
        String mimeType,
        long size,
        Instant expiresAt
    ) {
        /**
         * 处理{@code downloadUrl}并返回对应结果。
         *
         * @return 处理结果
         */
        public String downloadUrl() {
            return "/api/v1/chat/generated-files/" + artifactId + "?token=" + token;
        }

        /**
         * 将输入数据转换为{@code olPayload}。
         *
         * @return 处理结果
         */
        public Map<String, Object> toolPayload() {
            return Map.of(
                "filename", fileName,
                "mime_type", mimeType,
                "size", size,
                "download_url", downloadUrl()
            );
        }
    }

    /**
     * 封装Generated文件相关的不可变数据。
     */
    public record GeneratedFile(
        String artifactId,
        Path path,
        String fileName,
        String mimeType,
        long size,
        Instant expiresAt
    ) {
    }
}
