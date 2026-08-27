package group.aitools.nhs.platform.skill.service;

import group.aitools.nhs.platform.skill.domain.AgentSkillDependencyInstall;
import group.aitools.nhs.platform.skill.mapper.SkillDependencyInstallMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * 负责技能Dependency运行时Mount相关的业务编排与领域规则处理。
 *
 * Resolves and materializes an already-installed Skill dependency cache for one frozen run.
 *
 * <p>Dependency installation is deliberately kept outside the run path.  This service only
 * consumes a {@code succeeded} V84 record whose version and canonical dependency hash match the
 * run snapshot.  It never invokes pip/npm and never follows links from the cache directory.</p>
 */
@Service
public class SkillDependencyRuntimeMountService {

    /**
 * 创建 {@code SkillDependencyRuntimeMountService} 实例并初始化所需依赖。
 * Reserved directory inside a mounted Skill; it is not part of the authored bundle. */
    public static final String INJECTED_DIRECTORY = ".agent-dependencies";

    private static final long MAX_CACHE_BYTES = 256L * 1024 * 1024;
    private static final long MAX_CACHE_FILE_BYTES = 32L * 1024 * 1024;
    private static final int MAX_CACHE_FILES = 16_384;

    private final SkillDependencyInstallMapper installMapper;
    private final JsonMapper jsonMapper;
    private final Path installRoot;

    @Autowired
    public SkillDependencyRuntimeMountService(
        SkillDependencyInstallMapper installMapper,
        JsonMapper jsonMapper,
        @Value("${agent.skill.dependencies.root:./data/skill-dependencies}") String installRoot
    ) {
        this(installMapper, jsonMapper, Path.of(installRoot));
    }

    /**
     * 创建 {@code SkillDependencyRuntimeMountService} 实例并初始化所需依赖。
     *
     * @param installMapper {@code installMapper}参数
     * @param jsonMapper {@code jsonMapper}参数
     * @param installRoot {@code installRoot}参数
     */
    SkillDependencyRuntimeMountService(
        SkillDependencyInstallMapper installMapper,
        JsonMapper jsonMapper,
        Path installRoot
    ) {
        this.installMapper = java.util.Objects.requireNonNull(installMapper, "installMapper");
        this.jsonMapper = java.util.Objects.requireNonNull(jsonMapper, "jsonMapper");
        this.installRoot = java.util.Objects.requireNonNull(installRoot, "installRoot")
            .toAbsolutePath().normalize();
    }

    /**
 * 处理{@code mount}相关逻辑。
 *
     * Injects declared dependencies under {@code skillRoot/.agent-dependencies}.
     *
     * <p>An empty declaration is a no-op.  A non-empty declaration requires an exact, successful
     * V84 installation record; missing, stale, failed or blocked records are runtime-unavailable
     * instead of being treated as an empty dependency environment.</p>
     */
    public void mount(
        Long skillId,
        Long versionId,
        String skillKey,
        Map<String, Object> runtimeRequirements,
        Path skillRoot
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        requirePositive(skillId, "Skill 资源 ID");
        requirePositive(versionId, "Skill 版本 ID");
        if (skillKey == null || skillKey.isBlank()) {
            throw unavailable("Skill 标识无效");
        }
        if (skillRoot == null) {
            throw unavailable("Skill 挂载路径无效");
        }

        Map<String, List<String>> dependencies;
        try {
            Object raw = runtimeRequirements == null ? null : runtimeRequirements.get("dependencies");
            dependencies = SkillDependencySpec.lists(raw);
        } catch (RuntimeException exception) {
            throw unavailable("冻结 Skill 依赖声明无效");
        }
        if (dependencies.values().stream().allMatch(List::isEmpty)) {
            return;
        }

        String dependencyHash = SkillDependencySpec.hash(dependencies, jsonMapper);
        AgentSkillDependencyInstall state = installMapper.select(versionId, dependencyHash);
        if (state == null || !"succeeded".equals(state.getStatus())) {
            String status = state == null || state.getStatus() == null
                ? "not_installed" : state.getStatus();
            throw unavailable(skillKey + " 依赖安装状态不可用：" + status);
        }
        if (!java.util.Objects.equals(skillId, state.getSkillId())
            || !java.util.Objects.equals(versionId, state.getVersionId())
            || !dependencyHash.equalsIgnoreCase(state.getDependencyHash())) {
            throw unavailable(skillKey + " 依赖安装记录与冻结版本不一致");
        }

        String expectedRelativeRoot = expectedRelativeRoot(skillId, versionId, dependencyHash);
        if (!expectedRelativeRoot.equals(state.getInstallRoot())) {
            throw unavailable(skillKey + " 依赖缓存路径与冻结版本不一致");
        }
        Path cacheRoot = resolveCacheRoot(expectedRelativeRoot, skillKey);
        Path destinationRoot = skillRoot.toAbsolutePath().normalize()
            .resolve(INJECTED_DIRECTORY).normalize();
        if (!destinationRoot.startsWith(skillRoot.toAbsolutePath().normalize())) {
            throw unavailable(skillKey + " 依赖注入路径越出 Skill 目录");
        }
        rejectSymbolicLink(skillRoot, "Skill 挂载路径");
        if (Files.exists(destinationRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw unavailable(skillKey + " 依赖注入目录已存在");
        }

        CopyBudget budget = new CopyBudget();
        try {
            Files.createDirectory(destinationRoot);
            for (Map.Entry<String, List<String>> entry : dependencies.entrySet()) {
                if (entry.getValue() == null || entry.getValue().isEmpty()) {
                    continue;
                }
                Path source = cacheRoot.resolve(entry.getKey()).normalize();
                Path target = destinationRoot.resolve(entry.getKey()).normalize();
                if (!target.startsWith(destinationRoot)) {
                    throw unavailable(skillKey + " 依赖类型路径无效");
                }
                copyTree(source, target, budget, skillKey);
            }
        } catch (RuntimeException | IOException failure) {
            removeInjectedTree(destinationRoot, skillRoot);
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw unavailable(skillKey + " 依赖缓存注入失败");
        }
    }

    /**
     * 获取缓存Root。
     *
     * @param relativeRoot {@code relativeRoot}参数
     * @param skillKey 技能Key参数
     * @return 处理结果
     */
    private Path resolveCacheRoot(String relativeRoot, String skillKey) {
        rejectSymbolicLink(installRoot, "依赖缓存根目录");
        if (!Files.isDirectory(installRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw unavailable(skillKey + " 依赖缓存根目录不存在");
        }
        Path target = installRoot.resolve(relativeRoot).normalize();
        if (!target.startsWith(installRoot)) {
            throw unavailable(skillKey + " 依赖缓存路径越界");
        }
        rejectSymbolicLinkParents(installRoot, target);
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw unavailable(skillKey + " 依赖缓存目录不存在");
        }
        return target;
    }

    /**
     * 处理{@code copyTree}相关逻辑。
     *
     * @param source 数据源参数
     * @param target {@code target}参数
     * @param budget {@code budget}参数
     * @param skillKey 技能Key参数
     * @throws IOException 当处理过程无法正常完成时抛出
     */
    private void copyTree(Path source, Path target, CopyBudget budget, String skillKey)
        throws IOException {
        rejectSymbolicLink(source, "依赖缓存目录");
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw unavailable(skillKey + " 缺少 " + source.getFileName() + " 依赖缓存");
        }
        Files.createDirectory(target);
        Files.walkFileTree(
            source,
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
                    rejectSymbolicLink(directory, "依赖缓存目录");
                    if (!directory.equals(source)) {
                        Path relative = source.relativize(directory);
                        Path destination = target.resolve(relative).normalize();
                        if (!destination.startsWith(target) || Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                            throw unavailable(skillKey + " 依赖缓存目录结构无效");
                        }
                        try {
                            Files.createDirectory(destination);
                        } catch (IOException exception) {
                            throw unavailable(skillKey + " 依赖缓存目录复制失败");
                        }
                    }
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
                    // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
                    rejectSymbolicLink(file, "依赖缓存文件");
                    if (!attrs.isRegularFile() || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                        throw unavailable(skillKey + " 依赖缓存包含非普通文件");
                    }
                    long size = Files.size(file);
                    if (size < 0 || size > MAX_CACHE_FILE_BYTES
                        || budget.files >= MAX_CACHE_FILES
                        || budget.bytes > MAX_CACHE_BYTES - size) {
                        throw unavailable(skillKey + " 依赖缓存超过运行时上限");
                    }
                    Path relative = source.relativize(file);
                    Path destination = target.resolve(relative).normalize();
                    if (!destination.startsWith(target)
                        || Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                        throw unavailable(skillKey + " 依赖缓存文件路径无效");
                    }
                    Files.copy(file, destination, StandardCopyOption.COPY_ATTRIBUTES);
                    if (Files.size(destination) != size) {
                        throw unavailable(skillKey + " 依赖缓存文件复制校验失败");
                    }
                    budget.files++;
                    budget.bytes += size;
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
                    if (exception != null) {
                        throw exception;
                    }
                    return FileVisitResult.CONTINUE;
                }
            }
        );
    }

    /**
     * 处理{@code expectedRelativeRoot}并返回对应结果。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @param dependencyHash {@code dependencyHash}参数
     * @return 处理结果
     */
    private String expectedRelativeRoot(Long skillId, Long versionId, String dependencyHash) {
        return "skill-" + skillId + "/version-" + versionId + "/" + dependencyHash.substring(0, 16);
    }

    /**
     * 删除{@code InjectedTree}。
     *
     * @param target {@code target}参数
     * @param skillRoot 技能Root参数
     */
    private void removeInjectedTree(Path target, Path skillRoot) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (target == null || skillRoot == null || !target.startsWith(skillRoot.toAbsolutePath().normalize())) {
            return;
        }
        if (Files.isSymbolicLink(target)) {
            return;
        }
        try {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                Files.walkFileTree(target, new java.nio.file.SimpleFileVisitor<>() {
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
                        if (exception != null) {
                            throw exception;
                        }
                        Files.deleteIfExists(directory);
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        } catch (IOException ignored) {
            // The owning SkillRuntimeMountService clears the whole skills tree on any failure.
        }
    }

    /**
     * 处理{@code rejectSymbolicLinkParents}相关逻辑。
     *
     * @param root {@code root}参数
     * @param target {@code target}参数
     */
    private void rejectSymbolicLinkParents(Path root, Path target) {
        Path ancestor = target;
        while (ancestor != null && ancestor.startsWith(root)) {
            rejectSymbolicLink(ancestor, "依赖缓存路径");
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
     * 校验{@code Positive}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     */
    private void requirePositive(Long value, String label) {
        if (value == null || value <= 0) {
            throw unavailable(label + "无效");
        }
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

    /**
     * 表示{@code CopyBudget}相关的领域对象。
     */
    private static final class CopyBudget {
        private int files;
        private long bytes;
    }
}
