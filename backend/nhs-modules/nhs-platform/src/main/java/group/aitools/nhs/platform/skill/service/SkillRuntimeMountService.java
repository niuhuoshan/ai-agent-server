package group.aitools.nhs.platform.skill.service;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.skill.domain.AgentSkillFile;
import group.aitools.nhs.platform.skill.mapper.SkillFileMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 负责技能运行时Mount相关的业务编排与领域规则处理。
 *
 * Materializes immutable Skill files into the isolated AgentScope workspace.
 *
 * <p>The run request carries the Skill identity, version and bundle hash.  File bytes are loaded
 * only for that exact version, then every path and hash is checked before it is written.  This
 * keeps large binary bundles out of the run JSON while preserving frozen-version semantics.</p>
 */
@Service
public class SkillRuntimeMountService {

    private static final Pattern SKILL_KEY = Pattern.compile("[a-z][a-z0-9._-]{0,127}");
    private static final int MAX_FILES = 256;
    private static final long MAX_BYTES = 32L * 1024 * 1024;
    private static final int MAX_FILE_BYTES = 5 * 1024 * 1024;

    private final SkillFileMapper fileMapper;
    /**
 * 创建 {@code SkillRuntimeMountService} 实例并初始化所需依赖。
 * Optional only for legacy embedders that do not provide the V84 dependency mapper. */
    private SkillDependencyRuntimeMountService dependencyRuntimeMountService;

    public SkillRuntimeMountService(SkillFileMapper fileMapper) {
        this.fileMapper = fileMapper;
    }

    /**
 * 设置Dependency运行时MountService。
 * Injects the explicit V84 dependency cache consumer without changing legacy constructors. */
    @Autowired(required = false)
    public void setDependencyRuntimeMountService(
        SkillDependencyRuntimeMountService dependencyRuntimeMountService
    ) {
        this.dependencyRuntimeMountService = dependencyRuntimeMountService;
    }

    /**
 * 处理{@code mount}相关逻辑。
 * Mounts every Skill bound by the immutable run snapshot under {@code workspace/skills}. */
    public void mount(AgentRunRequest request, Path workspace) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (request == null || workspace == null) {
            throw new IllegalArgumentException("运行请求和工作区不能为空");
        }
        Object rawBindings = request.attributes().get("resourceBindings");
        Path workspaceRoot = workspace.toAbsolutePath().normalize();
        rejectSymbolicLink(workspaceRoot, "运行工作区");
        Path skillsRoot = workspaceRoot.resolve("skills").normalize();
        ensureChild(workspaceRoot, skillsRoot, "Skill 挂载根目录");
        resetSkillsRoot(skillsRoot);
        if (!(rawBindings instanceof List<?> bindings)) {
            return;
        }

        Set<String> mountedKeys = new HashSet<>();
        try {
            for (Object rawBinding : bindings) {
                if (!(rawBinding instanceof Map<?, ?> binding)
                    || !"skill".equals(String.valueOf(binding.get("resourceType")))) {
                    continue;
                }
                mountBinding(binding, skillsRoot, mountedKeys);
            }
        } catch (RuntimeException | Error failure) {
            // Do not leave a partially materialized bundle available to a resumed runner.
            resetSkillsRoot(skillsRoot);
            throw failure;
        }
    }

    /**
     * 处理{@code mountBinding}相关逻辑。
     *
     * @param rawBinding {@code rawBinding}参数
     * @param skillsRoot {@code skillsRoot}参数
     * @param mountedKeys {@code mountedKeys}参数
     */
    private void mountBinding(Map<?, ?> rawBinding, Path skillsRoot, Set<String> mountedKeys) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        Long skillId = positiveLong(rawBinding.get("resourceId"), "Skill 资源 ID");
        String permission = text(rawBinding.get("permission"));
        if (!Set.of("use", "invoke", "admin").contains(permission)) {
            throw unavailable("Skill 绑定权限无效");
        }
        Map<String, Object> config = map(rawBinding.get("config"), "Skill 绑定配置");
        Map<String, Object> snapshot = map(config.get("resourceSnapshot"), "Skill 资源快照");
        String skillKey = text(snapshot.get("skillKey"));
        if (skillKey == null || !SKILL_KEY.matcher(skillKey).matches()) {
            throw unavailable("冻结 Skill 标识无效");
        }
        if (!mountedKeys.add(skillKey)) {
            throw unavailable("运行快照包含重复 Skill：" + skillKey);
        }
        Long versionId = positiveLong(snapshot.get("versionId"), "Skill 版本 ID");
        String expectedBundleHash = text(snapshot.get("fileBundleHash"));
        List<AgentSkillFile> files = fileMapper.selectFiles(skillId, versionId);
        if (files == null || files.isEmpty()) {
            throw unavailable(skillKey + " 没有冻结文件包");
        }
        validateBundle(skillKey, expectedBundleHash, files);
        rejectReservedDependencyPath(skillKey, files);

        Path skillRoot = skillsRoot.resolve(skillKey).normalize();
        ensureChild(skillsRoot, skillRoot, "Skill 挂载路径");
        rejectSymbolicLink(skillRoot, "Skill 挂载路径");
        ensureDirectory(skillRoot, "Skill 挂载路径");
        for (AgentSkillFile file : files.stream()
            .filter(item -> "0".equals(item.getDelFlag()))
            .sorted(Comparator.comparing(AgentSkillFile::getPath))
            .toList()) {
            writeFile(skillRoot, file);
        }
        Map<String, Object> runtimeRequirements = optionalMap(
            snapshot.get("runtimeRequirements"), "Skill 运行要求"
        );
        if (dependencyRuntimeMountService == null) {
            // A legacy embedder may omit the V84 mapper, but it must not silently run a Skill
            // whose frozen declaration requires packages that cannot be mounted.
            try {
                if (SkillDependencySpec.lists(runtimeRequirements.get("dependencies"))
                    .values().stream().anyMatch(items -> !items.isEmpty())) {
                    throw unavailable(skillKey + " 依赖缓存运行时未配置");
                }
            } catch (RuntimeException exception) {
                if (exception instanceof IllegalStateException state
                    && state.getMessage() != null
                    && state.getMessage().startsWith("skill_runtime_unavailable:")) {
                    throw state;
                }
                throw unavailable(skillKey + " 冻结依赖声明无效");
            }
        } else {
            dependencyRuntimeMountService.mount(
                skillId, versionId, skillKey, runtimeRequirements, skillRoot
            );
        }
    }

    /**
     * 校验{@code Bundle}，并在条件不满足时终止处理。
     *
     * @param skillKey 技能Key参数
     * @param expectedHash {@code expectedHash}参数
     * @param files {@code files}参数
     */
    private void validateBundle(String skillKey, String expectedHash, List<AgentSkillFile> files) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (files == null) {
            throw unavailable(skillKey + " 文件包读取失败");
        }
        if (files.stream().anyMatch(java.util.Objects::isNull)) {
            throw unavailable(skillKey + " 包含空文件记录");
        }
        List<AgentSkillFile> active = files.stream()
            .filter(item -> "0".equals(item.getDelFlag()))
            .toList();
        if (active.size() > MAX_FILES) {
            throw unavailable(skillKey + " 文件数量超过运行时上限");
        }
        long total = 0;
        Set<String> paths = new HashSet<>();
        Map<String, String> kinds = new LinkedHashMap<>();
        for (AgentSkillFile file : active) {
            if (file == null) {
                throw unavailable(skillKey + " 包含空文件记录");
            }
            String path = file.getPath();
            validatePath(path);
            if (!paths.add(path)) {
                throw unavailable(skillKey + " 包含重复路径：" + path);
            }
            String kind = file.getFileKind();
            if (!"file".equals(kind) && !"directory".equals(kind)) {
                throw unavailable("Skill 文件类型无效：" + path);
            }
            kinds.put(path, kind);
            if ("directory".equals(kind)) {
                if (file.getSizeBytes() == null || file.getSizeBytes() != 0
                    || file.getContent() != null || file.getContentBytes() != null
                    || !ContentHashing.sha256("").equalsIgnoreCase(file.getContentHash())) {
                    throw unavailable(skillKey + " 目录记录无效：" + path);
                }
                continue;
            }
            if (file.getSizeBytes() == null || file.getSizeBytes() < 0
                || file.getSizeBytes() > MAX_FILE_BYTES) {
                throw unavailable(skillKey + " 包含超限文件");
            }
            byte[] bytes = bytes(file);
            if (bytes.length != file.getSizeBytes()) {
                throw unavailable(skillKey + " 文件大小校验失败：" + path);
            }
            total += bytes.length;
            if (total > MAX_BYTES) {
                throw unavailable(skillKey + " 文件包超过 32MB 运行时上限");
            }
            if (!ContentHashing.sha256(bytes).equalsIgnoreCase(file.getContentHash())) {
                throw unavailable(skillKey + " 文件哈希校验失败：" + path);
            }
        }
        for (Map.Entry<String, String> entry : kinds.entrySet()) {
            Path path = Path.of(entry.getKey());
            for (Path parent = path.getParent(); parent != null; parent = parent.getParent()) {
                String parentPath = parent.toString().replace(java.io.File.separatorChar, '/');
                if (kinds.containsKey(parentPath) && !"directory".equals(kinds.get(parentPath))) {
                    throw unavailable(skillKey + " 文件路径父级不是目录：" + entry.getKey());
                }
            }
        }
        if (!"file".equals(kinds.get("SKILL.md"))) {
            throw unavailable(skillKey + " 缺少 SKILL.md");
        }
        String actual = active.stream()
            .sorted(Comparator.comparing(AgentSkillFile::getPath))
            .map(file -> file.getPath() + "\n" + file.getFileKind() + "\n" + file.getContentHash())
            .collect(Collectors.collectingAndThen(Collectors.joining("\n"), ContentHashing::sha256));
        if (expectedHash != null && !expectedHash.isBlank() && !actual.equalsIgnoreCase(expectedHash)) {
            throw unavailable(skillKey + " 文件包哈希与冻结快照不一致");
        }
    }

    /**
     * 处理{@code rejectReservedDependencyPath}相关逻辑。
     *
     * @param skillKey 技能Key参数
     * @param files {@code files}参数
     */
    private void rejectReservedDependencyPath(String skillKey, List<AgentSkillFile> files) {
        String reserved = SkillDependencyRuntimeMountService.INJECTED_DIRECTORY;
        if (files.stream()
            .filter(item -> "0".equals(item.getDelFlag()))
            .map(AgentSkillFile::getPath)
            .anyMatch(path -> reserved.equals(path) || path.startsWith(reserved + "/"))) {
            throw unavailable(skillKey + " 使用了运行时依赖保留目录");
        }
    }

    /**
     * 处理write文件相关逻辑。
     *
     * @param skillRoot 技能Root参数
     * @param file 文件参数
     */
    private void writeFile(Path skillRoot, AgentSkillFile file) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        validatePath(file.getPath());
        Path target = skillRoot.resolve(file.getPath()).normalize();
        ensureChild(skillRoot, target, "Skill 文件路径");
        rejectSymbolicLinkParents(skillRoot, target);
        rejectSymbolicLink(target, "Skill 文件路径");
        if ("directory".equals(file.getFileKind())) {
            ensureDirectory(target, "Skill 目录");
            return;
        }
        if (!"file".equals(file.getFileKind())) {
            throw unavailable("Skill 文件类型无效：" + file.getPath());
        }
        Path parent = target.getParent();
        ensureDirectory(parent, "Skill 文件父目录");
        byte[] bytes = bytes(file);
        try {
            Path temporary = Files.createTempFile(parent, ".skill-", ".part");
            try {
                Files.write(temporary, bytes);
                moveReplacing(temporary, target);
                makeExecutableIfScript(target, file.getPath());
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            throw unavailable("Skill 文件挂载失败：" + file.getPath());
        }
    }

    /**
     * 处理{@code bytes}并返回对应结果。
     *
     * @param file 文件参数
     * @return 处理结果
     */
    private byte[] bytes(AgentSkillFile file) {
        if ("binary".equals(file.getContentEncoding())
            && file.getContent() == null && file.getContentBytes() != null) {
            return file.getContentBytes().clone();
        }
        if ("utf8".equals(file.getContentEncoding())
            && file.getContent() != null && file.getContentBytes() == null) {
            return file.getContent().getBytes(StandardCharsets.UTF_8);
        }
        throw unavailable("Skill 文件编码无效：" + file.getPath());
    }

    /**
     * 处理{@code moveReplacing}相关逻辑。
     *
     * @param source 数据源参数
     * @param target {@code target}参数
     * @throws IOException 当处理过程无法正常完成时抛出
     */
    private void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 处理{@code makeExecutableIfScript}相关逻辑。
     *
     * @param path {@code path}参数
     * @param rawPath {@code rawPath}参数
     */
    private void makeExecutableIfScript(Path path, String rawPath) {
        String lower = rawPath.toLowerCase(java.util.Locale.ROOT);
        if (!(lower.startsWith("scripts/") || lower.endsWith(".sh") || lower.endsWith(".py"))) {
            return;
        }
        try {
            Set<PosixFilePermission> permissions = new HashSet<>(Files.getPosixFilePermissions(path));
            permissions.add(PosixFilePermission.OWNER_READ);
            permissions.add(PosixFilePermission.OWNER_WRITE);
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows and non-POSIX volumes do not expose executable bits; the runner still
            // invokes scripts through an explicit interpreter and does not rely on this flag.
        }
    }

    /**
     * 校验目录，并在条件不满足时终止处理。
     *
     * @param path {@code path}参数
     * @param label {@code label}参数
     */
    private void ensureDirectory(Path path, String label) {
        if (path == null) {
            throw unavailable(label + "无效");
        }
        rejectSymbolicLink(path, label);
        try {
            Files.createDirectories(path);
        } catch (IOException exception) {
            throw unavailable(label + "创建失败");
        }
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw unavailable(label + "不是目录");
        }
    }

    /**
 * 清理或重置{@code SkillsRoot}。
 *
     * The workspace key can be reused while a conversation is resumed.  The reserved Skill tree
     * must therefore be rebuilt from the immutable snapshot on every invocation; otherwise a
     * removed binding could continue to expose files from an earlier turn.
     */
    private void resetSkillsRoot(Path skillsRoot) {
        rejectSymbolicLink(skillsRoot, "Skill 挂载根目录");
        if (Files.exists(skillsRoot, LinkOption.NOFOLLOW_LINKS)) {
            deleteTree(skillsRoot);
        }
        ensureDirectory(skillsRoot, "Skill 挂载根目录");
    }

    /**
     * 删除{@code Tree}。
     *
     * @param root {@code root}参数
     */
    private void deleteTree(Path root) {
        try {
            Files.walkFileTree(
                root,
                EnumSet.noneOf(FileVisitOption.class),
                Integer.MAX_VALUE,
                new FileVisitor<>() {
                    /**
                     * 处理preVisit目录并返回对应结果。
                     *
                     * @param directory 目录参数
                     * @param attrs {@code attrs}参数
                     * @return 处理结果
                     */
                    @Override
                    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                        rejectSymbolicLink(directory, "Skill 挂载路径");
                        return FileVisitResult.CONTINUE;
                    }

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
                        // walkFileTree does not follow links without FOLLOW_LINKS, so deleting a
                        // link removes only the link and can never traverse outside the workspace.
                        Files.deleteIfExists(file);
                        return FileVisitResult.CONTINUE;
                    }

                    /**
                     * 处理visit文件Failed并返回对应结果。
                     *
                     * @param file 文件参数
                     * @param exception {@code exception}参数
                     * @return 处理结果
                     * @throws IOException 当处理过程无法正常完成时抛出
                     */
                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exception)
                        throws IOException {
                        throw exception;
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
                }
            );
        } catch (IOException exception) {
            throw unavailable("清理 Skill 挂载目录失败");
        }
    }

    /**
     * 处理{@code rejectSymbolicLinkParents}相关逻辑。
     *
     * @param root {@code root}参数
     * @param target {@code target}参数
     */
    private void rejectSymbolicLinkParents(Path root, Path target) {
        Path ancestor = target.getParent();
        while (ancestor != null && ancestor.startsWith(root)) {
            rejectSymbolicLink(ancestor, "Skill 路径");
            if (ancestor.equals(root)) {
                break;
            }
            ancestor = ancestor.getParent();
        }
    }

    /**
     * 处理{@code rejectSymbolicLink}相关逻辑。
     *
     * @param path {@code path}参数
     * @param label {@code label}参数
     */
    private void rejectSymbolicLink(Path path, String label) {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(path)) {
            throw unavailable(label + "不能是符号链接");
        }
    }

    /**
     * 校验{@code Child}，并在条件不满足时终止处理。
     *
     * @param root {@code root}参数
     * @param child {@code child}参数
     * @param label {@code label}参数
     */
    private void ensureChild(Path root, Path child, String label) {
        if (!child.startsWith(root)) {
            throw unavailable(label + "越出工作区");
        }
    }

    /**
     * 校验{@code Path}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     */
    private void validatePath(String value) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0 || value.indexOf('\\') >= 0) {
            throw unavailable("Skill 文件路径无效");
        }
        Path path = Path.of(value);
        if (path.isAbsolute() || value.startsWith("/") || value.startsWith("//")) {
            throw unavailable("Skill 文件路径不能是绝对路径");
        }
        for (String segment : value.split("/")) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw unavailable("Skill 文件路径包含非法段");
            }
        }
    }

    /**
     * 将输入数据转换为{@code map}。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private Map<String, Object> map(Object value, String label) {
        if (!(value instanceof Map<?, ?> source)) {
            throw unavailable(label + "无效");
        }
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return Map.copyOf(result);
    }

    /**
     * 处理{@code optionalMap}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private Map<String, Object> optionalMap(Object value, String label) {
        if (value == null) {
            return Map.of();
        }
        return map(value, label);
    }

    /**
     * 处理{@code text}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String text(Object value) {
        return value instanceof String text && !text.isBlank() ? text.strip() : null;
    }

    /**
     * 处理{@code positiveLong}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private Long positiveLong(Object value, String label) {
        if (!(value instanceof Number number) || number.longValue() <= 0
            || number.doubleValue() != number.longValue()) {
            throw unavailable(label + "无效");
        }
        return number.longValue();
    }

    /**
     * 处理{@code unavailable}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private IllegalStateException unavailable(String message) {
        return new IllegalStateException("skill_runtime_unavailable: " + message);
    }
}
