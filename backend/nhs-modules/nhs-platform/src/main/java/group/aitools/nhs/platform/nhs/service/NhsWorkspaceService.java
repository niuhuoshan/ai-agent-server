package group.aitools.nhs.platform.nhs.service;

import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * 负责Nhs工作空间相关的业务编排与领域规则处理。
 * Complete owner-isolated Nhs file workspace with recoverable trash semantics. */
@Service
public class NhsWorkspaceService {

    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    private static final long MAX_PREVIEW_BYTES = 5L * 1024 * 1024;
    private static final long MAX_TEXT_SEARCH_FILE_BYTES = 1L * 1024 * 1024;
    private static final int MAX_RESULTS = 1000;
    private static final int MAX_RECENT_FILES = 20;
    private static final String TRASH_DIR = ".trash";
    private static final String PREFS_FILE = ".browser-prefs.json";
    private static final String RECENT_FILE = ".recent-files.json";
    private static final TypeReference<Map<String, Map<String, Object>>> TRASH_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> OBJECT_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, Object>>> LIST_TYPE = new TypeReference<>() {
    };

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final Path workspaceRoot;
    private final JsonMapper jsonMapper;
    /**
 * 创建 {@code NhsWorkspaceService} 实例并初始化所需依赖。
 *
     * Runtime tools execute outside an HTTP login context. The override is scoped to
     * one call and held in a ThreadLocal so concurrent users never share workspace roots.
     */
    private final ThreadLocal<CurrentPrincipal> runtimePrincipal = new ThreadLocal<>();

    public NhsWorkspaceService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        JsonMapper jsonMapper,
        @Value("${agent.platform.workspace-root:./data/agent-workspaces}") String workspaceRoot
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.workspaceRoot = Path.of(workspaceRoot).toAbsolutePath().normalize();
        this.jsonMapper = jsonMapper;
    }

    /**
 * 执行As运行时操作主体相关的处理流程。
 * Executes an existing workspace operation against a frozen runtime principal. */
    public <T> T runAsRuntimePrincipal(CurrentPrincipal principal, Supplier<T> operation) {
        Objects.requireNonNull(principal, "runtime principal must not be null");
        Objects.requireNonNull(operation, "workspace operation must not be null");
        CurrentPrincipal previous = runtimePrincipal.get();
        runtimePrincipal.set(principal);
        try {
            return operation.get();
        } finally {
            if (previous == null) {
                runtimePrincipal.remove();
            } else {
                runtimePrincipal.set(previous);
            }
        }
    }

    /**
 * 获取运行时文件。
 *
     * Resolves an existing private workspace file for a runtime tool after applying the same
     * owner and path checks as the interactive workspace API. The returned path is still inside
     * the owner's workspace after the scoped principal is restored.
     */
    public Path resolveRuntimeFile(CurrentPrincipal principal, String path, boolean write) {
        return runAsRuntimePrincipal(principal, () -> {
            authorize(write ? "write" : "read");
            return resolve(path, true);
        });
    }

    /**
     * 查询{@code list}列表。
     *
     * @param path {@code path}参数
     * @return 符合条件的数据集合
     */
    public List<Map<String, Object>> list(String path) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        authorize("read");
        Path directory = resolve(path, false);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw error("工作区目录不存在", HttpStatus.NOT_FOUND);
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Path entry : entries) {
                if (isInternal(entry)) {
                    continue;
                }
                result.add(fileEntry(entry));
            }
            result.sort(Comparator.comparing(item -> String.valueOf(item.get("name"))));
            return result;
        } catch (IOException exception) {
            throw error("读取工作区失败", HttpStatus.ERROR);
        }
    }

    /**
     * 处理{@code preview}并返回对应结果。
     *
     * @param path {@code path}参数
     * @return 处理结果
     */
    public Map<String, Object> preview(String path) {
        authorize("read");
        Path file = resolve(path, true);
        try {
            long size = Files.size(file);
            if (size > MAX_PREVIEW_BYTES) {
                throw error("文件超过预览上限", 413);
            }
            byte[] bytes = Files.readAllBytes(file);
            return Map.of(
                "path", relative(file),
                "content", new String(bytes, StandardCharsets.UTF_8),
                "size", bytes.length,
                "mime_type", Files.probeContentType(file) == null ? "text/plain" : Files.probeContentType(file)
            );
        } catch (IOException exception) {
            throw error("读取文件失败", HttpStatus.ERROR);
        }
    }

    /**
     * 处理{@code write}并返回对应结果。
     *
     * @param path {@code path}参数
     * @param content 待处理内容
     * @return 处理结果
     */
    public Map<String, Object> write(String path, String content) {
        authorize("write");
        if (content == null || content.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES) {
            throw error("文件内容为空或超过10MB限制", HttpStatus.BAD_REQUEST);
        }
        Path file = resolve(path, false);
        ensureParent(file);
        try {
            if (Files.exists(file) && Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
                throw error("目标是目录", HttpStatus.CONFLICT);
            }
            Files.writeString(file, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return fileEntry(file);
        } catch (IOException exception) {
            throw error("写入文件失败", HttpStatus.ERROR);
        }
    }

    /**
 * 处理write画布并返回对应结果。
 * Atomically saves bounded canvas bytes without silently replacing an existing file. */
    public Map<String, Object> writeCanvas(String path, byte[] content, boolean overwrite) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        authorize("write");
        if (content == null || content.length == 0 || content.length > MAX_FILE_BYTES) {
            throw error("画布文件内容为空或超过10MB限制", HttpStatus.BAD_REQUEST);
        }
        Path file = resolve(path, false);
        ensureParent(file);
        try {
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                && Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
                throw error("目标是目录", HttpStatus.CONFLICT);
            }
            if (overwrite) {
                Files.write(file, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } else {
                Files.write(file, content, StandardOpenOption.CREATE_NEW);
            }
            return fileEntry(file);
        } catch (FileAlreadyExistsException exception) {
            throw error("工作区已存在同名文件，请显式允许覆盖", HttpStatus.CONFLICT);
        } catch (IOException exception) {
            throw error("保存画布到工作区失败", HttpStatus.ERROR);
        }
    }

    /**
     * 创建并保存{@code Entry}。
     *
     * @param parentPath {@code parentPath}参数
     * @param name 名称
     * @param kind {@code kind}参数
     * @return 处理结果
     */
    public Map<String, Object> createEntry(String parentPath, String name, String kind) {
        authorize("write");
        validateName(name);
        Path parent = resolve(parentPath, false);
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw error("父目录不存在", HttpStatus.NOT_FOUND);
        }
        Path target = parent.resolve(name).normalize();
        assertInside(target);
        try {
            if ("dir".equalsIgnoreCase(kind) || "directory".equalsIgnoreCase(kind)) {
                Files.createDirectory(target);
            } else {
                Files.createFile(target);
            }
            return fileEntry(target);
        } catch (IOException exception) {
            throw error("创建工作区条目失败", HttpStatus.CONFLICT);
        }
    }

    /**
     * 处理{@code rename}并返回对应结果。
     *
     * @param path {@code path}参数
     * @param name 名称
     * @return 处理结果
     */
    public Map<String, Object> rename(String path, String name) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        authorize("write");
        validateName(name);
        Path source = existing(path);
        if (source.equals(root())) {
            throw error("不能重命名工作区根目录", HttpStatus.BAD_REQUEST);
        }
        Path target = source.resolveSibling(name).normalize();
        assertInside(target);
        try {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw error("目标名称已存在", HttpStatus.CONFLICT);
            }
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            return fileEntry(target);
        } catch (IOException exception) {
            try {
                Files.move(source, target);
                return fileEntry(target);
            } catch (IOException retry) {
                throw error("重命名工作区条目失败", HttpStatus.ERROR);
            }
        }
    }

    /**
     * 删除{@code delete}。
     *
     * @param path {@code path}参数
     * @return 处理结果
     */
    public Map<String, Object> delete(String path) {
        authorize("delete");
        Path source = existing(path);
        if (source.equals(root())) {
            throw error("不能删除工作区根目录", HttpStatus.BAD_REQUEST);
        }
        String id = UUID.randomUUID().toString();
        Path trash = trashDir().resolve(id).normalize();
        try {
            Files.createDirectories(trashDir());
            Files.move(source, trash, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            try {
                Files.move(source, trash);
            } catch (IOException retry) {
                throw error("移动到回收站失败", HttpStatus.ERROR);
            }
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", id);
        entry.put("original_path", relative(source));
        entry.put("is_dir", Files.isDirectory(trash, LinkOption.NOFOLLOW_LINKS));
        entry.put("deleted_at", Instant.now().toString());
        Map<String, Map<String, Object>> index = trashIndex();
        index.put(id, entry);
        writeTrashIndex(index);
        return Map.of("trash_id", id, "path", relative(source), "deleted", true);
    }

    /**
     * 处理{@code restore}并返回对应结果。
     *
     * @param trashId 资源标识
     * @param originalPath {@code originalPath}参数
     * @return 处理结果
     */
    public Map<String, Object> restore(String trashId, String originalPath) {
        authorize("write");
        Map<String, Map<String, Object>> index = trashIndex();
        String id = locateTrashId(index, trashId, originalPath);
        Map<String, Object> entry = index.get(id);
        Path source = trashDir().resolve(id).normalize();
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            index.remove(id);
            writeTrashIndex(index);
            throw error("回收站条目不存在", HttpStatus.NOT_FOUND);
        }
        String destinationRaw = String.valueOf(entry.get("original_path"));
        Path destination = resolve(destinationRaw, false);
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw error("原路径已有同名条目", HttpStatus.CONFLICT);
        }
        ensureParent(destination);
        try {
            Files.move(source, destination);
        } catch (IOException exception) {
            throw error("恢复回收站条目失败", HttpStatus.ERROR);
        }
        index.remove(id);
        writeTrashIndex(index);
        return Map.of("trash_id", id, "path", relative(destination), "restored", true);
    }

    /**
     * 处理{@code purge}并返回对应结果。
     *
     * @param trashId 资源标识
     * @param originalPath {@code originalPath}参数
     * @return 处理结果
     */
    public Map<String, Object> purge(String trashId, String originalPath) {
        authorize("delete");
        Map<String, Map<String, Object>> index = trashIndex();
        String id = locateTrashId(index, trashId, originalPath);
        Path target = trashDir().resolve(id).normalize();
        deleteRecursively(target);
        index.remove(id);
        writeTrashIndex(index);
        return Map.of("trash_id", id, "purged", true);
    }

    /**
     * 处理{@code emptyTrash}并返回对应结果。
     *
     * @return 处理结果
     */
    public Map<String, Object> emptyTrash() {
        authorize("delete");
        Map<String, Map<String, Object>> index = trashIndex();
        int count = index.size();
        for (String id : List.copyOf(index.keySet())) {
            deleteRecursively(trashDir().resolve(id).normalize());
        }
        index.clear();
        writeTrashIndex(index);
        return Map.of("purged_count", count, "emptied", true);
    }

    /**
     * 处理{@code trashEntries}并返回对应结果。
     *
     * @return 符合条件的数据集合
     */
    public List<Map<String, Object>> trashEntries() {
        authorize("read");
        return trashIndex().values().stream()
            .sorted((left, right) -> String.valueOf(right.get("deleted_at"))
                .compareTo(String.valueOf(left.get("deleted_at"))))
            .map(value -> Map.copyOf(value))
            .toList();
    }

    /**
     * 处理{@code upload}并返回对应结果。
     *
     * @param parentPath {@code parentPath}参数
     * @param file 文件参数
     * @return 处理结果
     */
    public Map<String, Object> upload(String parentPath, MultipartFile file) {
        authorize("write");
        if (file == null || file.isEmpty() || file.getSize() <= 0 || file.getSize() > MAX_FILE_BYTES) {
            throw error("上传文件为空或超过10MB限制", HttpStatus.BAD_REQUEST);
        }
        String name = safeName(file.getOriginalFilename());
        Path parent = resolve(parentPath, false);
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw error("上传目录不存在", HttpStatus.NOT_FOUND);
        }
        Path destination = parent.resolve(name).normalize();
        assertInside(destination);
        try (InputStream input = file.getInputStream()) {
            Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
            return fileEntry(destination);
        } catch (IOException exception) {
            throw error("上传文件失败", HttpStatus.ERROR);
        }
    }

    /**
     * 查询{@code search}列表。
     *
     * @param query 查询参数
     * @param path {@code path}参数
     * @return 符合条件的数据集合
     */
    public List<Map<String, Object>> search(String query, String path) {
        authorize("read");
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (needle.isBlank()) {
            throw error("搜索关键词不能为空", HttpStatus.BAD_REQUEST);
        }
        Path start = resolve(path, false);
        if (!Files.isDirectory(start, LinkOption.NOFOLLOW_LINKS)) {
            throw error("搜索目录不存在", HttpStatus.NOT_FOUND);
        }
        try (Stream<Path> stream = Files.walk(start, 16)) {
            return stream.filter(pathValue -> !isInternal(pathValue))
                .filter(pathValue -> pathValue.getFileName() != null
                    && pathValue.getFileName().toString().toLowerCase(Locale.ROOT).contains(needle))
                .limit(MAX_RESULTS)
                .map(pathValue -> {
                    try {
                        return fileEntry(pathValue);
                    } catch (IOException exception) {
                        return fallbackEntry(pathValue);
                    }
                }).toList();
        } catch (IOException exception) {
            throw error("搜索工作区失败", HttpStatus.ERROR);
        }
    }

    /**
 * 查询{@code Text}列表。
 * Searches file contents within the caller's private workspace. */
    public List<Map<String, Object>> searchText(String pattern, String path, int limit) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        authorize("read");
        String needle = pattern == null ? "" : pattern.strip();
        if (needle.isBlank() || needle.length() > 256 || needle.indexOf('\0') >= 0) {
            throw error("文本搜索关键词无效", HttpStatus.BAD_REQUEST);
        }
        Path start = resolve(path, false);
        if (!Files.isDirectory(start, LinkOption.NOFOLLOW_LINKS)) {
            throw error("搜索目录不存在", HttpStatus.NOT_FOUND);
        }
        int bounded = Math.max(1, Math.min(limit, MAX_RESULTS));
        String normalizedNeedle = needle.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> result = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(start, 16)) {
            for (Path candidate : stream
                .filter(value -> !isInternal(value))
                .filter(value -> Files.isRegularFile(value, LinkOption.NOFOLLOW_LINKS))
                .toList()) {
                if (result.size() >= bounded) {
                    break;
                }
                try {
                    if (Files.size(candidate) > MAX_TEXT_SEARCH_FILE_BYTES) {
                        continue;
                    }
                    String content = Files.readString(candidate, StandardCharsets.UTF_8);
                    String normalized = content.toLowerCase(Locale.ROOT);
                    int offset = normalized.indexOf(normalizedNeedle);
                    if (offset < 0) {
                        continue;
                    }
                    int line = 1;
                    for (int i = 0; i < offset && i < content.length(); i++) {
                        if (content.charAt(i) == '\n') {
                            line++;
                        }
                    }
                    int lineStart = content.lastIndexOf('\n', Math.max(0, offset - 1)) + 1;
                    int lineEnd = content.indexOf('\n', offset);
                    if (lineEnd < 0) {
                        lineEnd = content.length();
                    }
                    Map<String, Object> hit = new LinkedHashMap<>();
                    hit.put("path", relative(candidate));
                    hit.put("line", line);
                    hit.put("text", content.substring(lineStart, lineEnd));
                    result.add(Map.copyOf(hit));
                } catch (IOException | RuntimeException ignored) {
                    // Binary, unreadable, or concurrently removed files are skipped.
                }
            }
            return List.copyOf(result);
        } catch (IOException exception) {
            throw error("搜索工作区文本失败", HttpStatus.ERROR);
        }
    }

    /**
     * 处理{@code recent}并返回对应结果。
     *
     * @param path {@code path}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<Map<String, Object>> recent(String path, int limit) {
        authorize("read");
        Path start = resolve(path, false);
        int bounded = Math.max(1, Math.min(limit, MAX_RESULTS));
        try (Stream<Path> stream = Files.walk(start, 16)) {
            return stream.filter(value -> !value.equals(start) && !isInternal(value))
                .sorted((left, right) -> {
                    try {
                        return Files.getLastModifiedTime(right, LinkOption.NOFOLLOW_LINKS)
                            .compareTo(Files.getLastModifiedTime(left, LinkOption.NOFOLLOW_LINKS));
                    } catch (IOException exception) {
                        return 0;
                    }
                }).limit(bounded).map(value -> {
                    try {
                        return fileEntry(value);
                    } catch (IOException exception) {
                        return fallbackEntry(value);
                    }
                }).toList();
        } catch (IOException exception) {
            throw error("读取最近文件失败", HttpStatus.ERROR);
        }
    }

    /**
     * 处理{@code storedRecent}并返回对应结果。
     *
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<Map<String, Object>> storedRecent(int limit) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        authorize("read");
        int bounded = Math.max(1, Math.min(limit, MAX_RECENT_FILES));
        Path file = root().resolve(RECENT_FILE);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return recent("", bounded);
        }
        try {
            List<Map<String, Object>> stored = jsonMapper.readValue(Files.readString(file), LIST_TYPE);
            if (stored == null) {
                return List.of();
            }
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> item : stored) {
                String path = item == null ? null : Objects.toString(item.get("path"), null);
                if (path == null || path.isBlank()) {
                    continue;
                }
                try {
                    result.add(fileEntry(resolve(path, true)));
                } catch (ServiceException | IOException ignored) {
                    // Stale or no-longer-authorized entries disappear from the projection.
                }
                if (result.size() >= bounded) {
                    break;
                }
            }
            return List.copyOf(result);
        } catch (IOException | RuntimeException exception) {
            throw error("最近文件数据损坏", HttpStatus.ERROR);
        }
    }

    /**
     * 更新{@code Recent}。
     *
     * @param rawItems {@code rawItems}参数
     * @return 符合条件的数据集合
     */
    public List<Map<String, Object>> updateRecent(Object rawItems) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        authorize("write");
        if (!(rawItems instanceof List<?> items)) {
            throw error("最近文件列表不能为空", HttpStatus.BAD_REQUEST);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new java.util.LinkedHashSet<>();
        for (Object raw : items) {
            if (!(raw instanceof Map<?, ?> item)) {
                continue;
            }
            Object pathValue = item.get("path");
            if (pathValue == null || String.valueOf(pathValue).isBlank()) {
                continue;
            }
            try {
                Map<String, Object> entry = fileEntry(resolve(String.valueOf(pathValue), true));
                String normalized = String.valueOf(entry.get("path"));
                if (seen.add(normalized)) {
                    result.add(entry);
                }
            } catch (ServiceException | IOException ignored) {
                // Match Nhs's sanitizing semantics: invalid and inaccessible paths are discarded.
            }
            if (result.size() >= MAX_RECENT_FILES) {
                break;
            }
        }
        try {
            Files.writeString(root().resolve(RECENT_FILE), jsonMapper.writeValueAsString(result), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return List.copyOf(result);
        } catch (IOException | RuntimeException exception) {
            throw error("保存最近文件记录失败", HttpStatus.ERROR);
        }
    }

    /**
     * 处理浏览器Prefs并返回对应结果。
     *
     * @return 处理结果
     */
    public Map<String, Object> browserPrefs() {
        authorize("read");
        Path file = root().resolve(PREFS_FILE);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return Map.of();
        }
        try {
            return jsonMapper.readValue(Files.readString(file), OBJECT_TYPE);
        } catch (IOException | RuntimeException exception) {
            throw error("浏览器偏好数据损坏", HttpStatus.ERROR);
        }
    }

    /**
     * 更新浏览器Prefs。
     *
     * @param prefs {@code prefs}参数
     * @return 处理结果
     */
    public Map<String, Object> updateBrowserPrefs(Map<String, Object> prefs) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        authorize("write");
        if (prefs == null) {
            throw error("浏览器偏好不能为空", HttpStatus.BAD_REQUEST);
        }
        try {
            String json = jsonMapper.writeValueAsString(prefs);
            if (json.getBytes(StandardCharsets.UTF_8).length > 64 * 1024) {
                throw error("浏览器偏好超过64KB限制", HttpStatus.BAD_REQUEST);
            }
            ensureParent(root().resolve(PREFS_FILE));
            Files.writeString(root().resolve(PREFS_FILE), json, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return prefs;
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof ServiceException serviceException) {
                throw serviceException;
            }
            throw error("保存浏览器偏好失败", HttpStatus.ERROR);
        }
    }

    /**
     * 处理文件Entry并返回对应结果。
     *
     * @param path {@code path}参数
     * @return 处理结果
     * @throws IOException 当处理过程无法正常完成时抛出
     */
    private Map<String, Object> fileEntry(Path path) throws IOException {
        boolean directory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
        return Map.of(
            "name", path.getFileName().toString(),
            "path", relative(path),
            "is_dir", directory,
            "size", directory ? 0L : Files.size(path),
            "mtime", Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis(),
            "mime_type", directory ? "inode/directory" : Objects.requireNonNullElse(Files.probeContentType(path), "application/octet-stream")
        );
    }

    /**
     * 处理{@code fallbackEntry}并返回对应结果。
     *
     * @param path {@code path}参数
     * @return 处理结果
     */
    private Map<String, Object> fallbackEntry(Path path) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("path", relative(path));
        value.put("name", path.getFileName() == null ? "" : path.getFileName().toString());
        return value;
    }

    /**
     * 获取{@code resolve}。
     *
     * @param raw {@code raw}参数
     * @param requireFile require文件参数
     * @return 处理结果
     */
    private Path resolve(String raw, boolean requireFile) {
        Path root = root();
        String relative = raw == null || raw.isBlank() ? "" : raw.strip();
        if ("/".equals(relative) || ".".equals(relative)) {
            relative = "";
        }
        if (relative.indexOf('\0') >= 0 || relative.startsWith("/") || relative.startsWith("\\")) {
            throw error("工作区路径无效", HttpStatus.BAD_REQUEST);
        }
        Path result = root.resolve(relative).normalize();
        assertInside(result);
        if (requireFile && !Files.isRegularFile(result, LinkOption.NOFOLLOW_LINKS)) {
            throw error("工作区文件不存在", HttpStatus.NOT_FOUND);
        }
        return result;
    }

    /**
     * 处理{@code existing}并返回对应结果。
     *
     * @param raw {@code raw}参数
     * @return 处理结果
     */
    private Path existing(String raw) {
        Path value = resolve(raw, false);
        if (!Files.exists(value, LinkOption.NOFOLLOW_LINKS)) {
            throw error("工作区条目不存在", HttpStatus.NOT_FOUND);
        }
        return value;
    }

    /**
     * 处理{@code assertInside}相关逻辑。
     *
     * @param path {@code path}参数
     */
    private void assertInside(Path path) {
        if (!path.startsWith(root()) || path.equals(trashDir()) || path.startsWith(trashDir())) {
            throw error("工作区路径越权", HttpStatus.FORBIDDEN);
        }
        Path current = root();
        Path relative = root().relativize(path);
        for (Path part : relative) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw error("工作区不允许符号链接", HttpStatus.FORBIDDEN);
            }
        }
    }

    /**
     * 判断{@code Internal}是否满足要求。
     *
     * @param path {@code path}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isInternal(Path path) {
        return path.startsWith(trashDir()) || path.getFileName() != null
            && (TRASH_DIR.equals(path.getFileName().toString())
                || PREFS_FILE.equals(path.getFileName().toString())
                || RECENT_FILE.equals(path.getFileName().toString()));
    }

    /**
     * 处理{@code root}并返回对应结果。
     *
     * @return 处理结果
     */
    private Path root() {
        CurrentPrincipal principal = effectivePrincipal();
        Path root = workspaceRoot.resolve(principal.id().toString()).normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException exception) {
            throw error("无法初始化私有工作区", HttpStatus.ERROR);
        }
        return root;
    }

    /**
     * 处理{@code trashDir}并返回对应结果。
     *
     * @return 处理结果
     */
    private Path trashDir() {
        return root().resolve(TRASH_DIR).normalize();
    }

    /**
     * 处理{@code relative}并返回对应结果。
     *
     * @param path {@code path}参数
     * @return 处理结果
     */
    private String relative(Path path) {
        return root().relativize(path).toString().replace('\\', '/');
    }

    /**
     * 校验{@code Parent}，并在条件不满足时终止处理。
     *
     * @param path {@code path}参数
     */
    private void ensureParent(Path path) {
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException exception) {
            throw error("无法创建工作区目录", HttpStatus.ERROR);
        }
    }

    /**
     * 处理{@code trashIndex}并返回对应结果。
     *
     * @return 处理结果
     */
    private Map<String, Map<String, Object>> trashIndex() {
        try {
            Files.createDirectories(trashDir());
            Path index = trashDir().resolve("index.json");
            if (!Files.isRegularFile(index, LinkOption.NOFOLLOW_LINKS)) {
                return new LinkedHashMap<>();
            }
            Map<String, Map<String, Object>> value = jsonMapper.readValue(Files.readString(index), TRASH_TYPE);
            return value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
        } catch (IOException | RuntimeException exception) {
            throw error("回收站索引损坏", HttpStatus.ERROR);
        }
    }

    /**
     * 处理{@code writeTrashIndex}相关逻辑。
     *
     * @param index {@code index}参数
     */
    private void writeTrashIndex(Map<String, Map<String, Object>> index) {
        try {
            Files.createDirectories(trashDir());
            Files.writeString(trashDir().resolve("index.json"), jsonMapper.writeValueAsString(index), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException | RuntimeException exception) {
            throw error("保存回收站索引失败", HttpStatus.ERROR);
        }
    }

    /**
     * 处理{@code locateTrashId}并返回对应结果。
     *
     * @param index {@code index}参数
     * @param trashId 资源标识
     * @param originalPath {@code originalPath}参数
     * @return 处理结果
     */
    private String locateTrashId(Map<String, Map<String, Object>> index, String trashId, String originalPath) {
        if (trashId != null && !trashId.isBlank()) {
            String normalized = trashId.strip();
            if (index.containsKey(normalized)) {
                return normalized;
            }
            throw error("回收站条目不存在", HttpStatus.NOT_FOUND);
        }
        if (originalPath != null && !originalPath.isBlank()) {
            return index.entrySet().stream()
                .filter(entry -> originalPath.strip().equals(String.valueOf(entry.getValue().get("original_path"))))
                .map(Map.Entry::getKey).findFirst()
                .orElseThrow(() -> error("回收站条目不存在", HttpStatus.NOT_FOUND));
        }
        throw error("必须提供 trash_id 或 original_path", HttpStatus.BAD_REQUEST);
    }

    /**
     * 删除{@code Recursively}。
     *
     * @param target {@code target}参数
     */
    private void deleteRecursively(Path target) {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(target)) {
            stream.sorted(Comparator.reverseOrder()).forEach(value -> {
                try {
                    Files.deleteIfExists(value);
                } catch (IOException exception) {
                    throw new WorkspaceDeleteException(exception);
                }
            });
        } catch (IOException | WorkspaceDeleteException exception) {
            throw error("清理回收站失败", HttpStatus.ERROR);
        }
    }

    /**
     * 处理{@code safeName}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String safeName(String value) {
        if (value == null || value.isBlank()) {
            throw error("文件名不能为空", HttpStatus.BAD_REQUEST);
        }
        String name = Path.of(value).getFileName().toString();
        validateName(name);
        return name;
    }

    /**
     * 校验{@code Name}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     */
    private void validateName(String value) {
        if (value == null || value.isBlank() || ".".equals(value) || "..".equals(value)
            || value.contains("/") || value.contains("\\") || value.startsWith(".") || value.length() > 200) {
            throw error("工作区名称无效", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 处理{@code authorize}相关逻辑。
     *
     * @param action {@code action}参数
     */
    private void authorize(String action) {
        CurrentPrincipal principal = effectivePrincipal();
        authorizationEnforcer.requireAllowed(principal, new PermissionContext(
            "workspace", null, null, action, ResourceState.ACTIVE, true, Set.of(), null
        ));
    }

    /**
     * 处理effective操作主体并返回对应结果。
     *
     * @return 处理结果
     */
    private CurrentPrincipal effectivePrincipal() {
        CurrentPrincipal runtime = runtimePrincipal.get();
        return runtime == null ? principalProvider.currentPrincipal() : runtime;
    }

    /**
     * 处理{@code error}并返回对应结果。
     *
     * @param message 待处理内容
     * @param status 目标状态
     * @return 处理结果
     */
    private ServiceException error(String message, int status) {
        return new ServiceException(message, status);
    }

    /**
     * 表示工作空间Delete处理过程中发生的业务异常。
     */
    private static final class WorkspaceDeleteException extends RuntimeException {
        /**
         * 创建 {@code WorkspaceDeleteException} 实例并初始化所需依赖。
         *
         * @param cause {@code cause}参数
         */
        private WorkspaceDeleteException(IOException cause) {
            super(cause);
        }
    }
}
