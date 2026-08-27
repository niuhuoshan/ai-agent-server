package group.aitools.nhs.platform.skill.service;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.skill.domain.AgentSkillDependencyInstall;
import group.aitools.nhs.platform.skill.domain.AgentSkillVersion;
import group.aitools.nhs.platform.skill.mapper.SkillCatalogMapper;
import group.aitools.nhs.platform.skill.mapper.SkillDependencyInstallMapper;
import group.aitools.nhs.platform.skill.web.SkillDependencyInstallView;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 负责技能DependencyInstall相关的业务编排与领域规则处理。
 *
 * Explicit Skill dependency installer.  Installation is never implicit during a chat run;
 * operators enable this profile and trigger it from the resource center, producing a durable
 * status keyed by the immutable version and dependency hash.
 */
@Service
public class SkillDependencyInstallService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final int MAX_OUTPUT_BYTES = 32 * 1024;

    private final CurrentPrincipalProvider principalProvider;
    private final SkillCatalogService catalogService;
    private final SkillCatalogMapper catalogMapper;
    private final SkillDependencyInstallMapper installMapper;
    private final PlatformIdGenerator idGenerator;
    private final JsonMapper jsonMapper;
    private final boolean installerEnabled;
    private final Path installRoot;
    private final String pythonExecutable;
    private final String npmExecutable;
    private final long timeoutSeconds;

    /**
     * 创建 {@code SkillDependencyInstallService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param catalogService 目录Service参数
     * @param catalogMapper 目录Mapper参数
     * @param installMapper {@code installMapper}参数
     * @param idGenerator {@code idGenerator}参数
     * @param jsonMapper {@code jsonMapper}参数
     * @param installerEnabled {@code installerEnabled}参数
     * @param installRoot {@code installRoot}参数
     * @param pythonExecutable {@code pythonExecutable}参数
     * @param npmExecutable {@code npmExecutable}参数
     * @param timeoutSeconds {@code timeoutSeconds}参数
     */
    public SkillDependencyInstallService(
        CurrentPrincipalProvider principalProvider,
        SkillCatalogService catalogService,
        SkillCatalogMapper catalogMapper,
        SkillDependencyInstallMapper installMapper,
        PlatformIdGenerator idGenerator,
        JsonMapper jsonMapper,
        @Value("${agent.skill.dependencies.install-enabled:false}") boolean installerEnabled,
        @Value("${agent.skill.dependencies.root:./data/skill-dependencies}") String installRoot,
        @Value("${agent.skill.dependencies.python-executable:python3}") String pythonExecutable,
        @Value("${agent.skill.dependencies.npm-executable:npm}") String npmExecutable,
        @Value("${agent.skill.dependencies.timeout-seconds:300}") long timeoutSeconds
    ) {
        this.principalProvider = principalProvider;
        this.catalogService = catalogService;
        this.catalogMapper = catalogMapper;
        this.installMapper = installMapper;
        this.idGenerator = idGenerator;
        this.jsonMapper = jsonMapper;
        this.installerEnabled = installerEnabled;
        this.installRoot = Path.of(installRoot).toAbsolutePath().normalize();
        this.pythonExecutable = executable(pythonExecutable, "python");
        this.npmExecutable = executable(npmExecutable, "npm");
        if (timeoutSeconds < 1 || timeoutSeconds > 900) {
            throw new IllegalArgumentException("Skill dependency timeout must be between 1 and 900 seconds");
        }
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * 处理{@code inspect}并返回对应结果。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @return 处理结果
     */
    public SkillDependencyInstallView inspect(Long skillId, Long versionId) {
        catalogService.requireFileAccess(skillId, versionId, false);
        AgentSkillVersion version = requireVersion(skillId, versionId);
        Map<String, List<String>> dependencies = dependencies(version);
        String hash = dependencyHash(dependencies);
        return SkillDependencyInstallView.from(
            skillId, versionId, version.getVersionNo(), dependencies, hash,
            installMapper.select(versionId, hash), installerEnabled
        );
    }

    /**
     * 处理{@code install}并返回对应结果。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public SkillDependencyInstallView install(Long skillId, Long versionId) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        catalogService.requireRuntimeAccess(skillId, versionId);
        AgentSkillVersion version = requireVersion(skillId, versionId);
        if (!"published".equals(version.getStatus())) {
            throw new ServiceException("只有已发布的 Skill 版本可以安装依赖", HttpStatus.CONFLICT);
        }
        Map<String, List<String>> dependencies = dependencies(version);
        String hash = dependencyHash(dependencies);
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        LocalDateTime now = LocalDateTime.now();
        AgentSkillDependencyInstall state = installMapper.select(versionId, hash);
        int attempt = state == null || state.getAttemptNo() == null ? 1 : state.getAttemptNo() + 1;
        String relativeRoot = "skill-" + skillId + "/version-" + versionId + "/" + hash.substring(0, 16);
        if (state == null) {
            state = new AgentSkillDependencyInstall();
            state.setId(idGenerator.nextId());
            state.setSkillId(skillId);
            state.setVersionId(versionId);
            state.setDependencyHash(hash);
            state.setAttemptNo(attempt);
            state.setRequestedBy(principal.id());
            state.setRequestedAt(now);
            state.setInstallRoot(relativeRoot);
            state.setStatus("queued");
            state.setMessage("等待依赖安装器");
            installMapper.insert(state);
        } else {
            installMapper.update(
                versionId, hash, "queued", attempt, principal.id(), now, null,
                relativeRoot, "等待依赖安装器"
            );
        }

        if (dependencies.isEmpty()) {
            installMapper.update(
                versionId, hash, "skipped", attempt, principal.id(), now, now,
                relativeRoot, "当前版本未声明第三方依赖"
            );
            return inspect(skillId, versionId);
        }
        if (!installerEnabled) {
            installMapper.update(
                versionId, hash, "blocked", attempt, principal.id(), now, now,
                relativeRoot, "依赖安装器未启用，请在私有化部署配置中显式开启"
            );
            return inspect(skillId, versionId);
        }

        installMapper.update(
            versionId, hash, "running", attempt, principal.id(), now, null,
            relativeRoot, "正在安装依赖"
        );
        Path target = installRoot.resolve(relativeRoot).normalize();
        if (!target.startsWith(installRoot) || Files.isSymbolicLink(installRoot)) {
            return fail(skillId, versionId, hash, attempt, principal.id(), now, relativeRoot, "依赖缓存路径无效");
        }
        try {
            Files.createDirectories(target);
            ProcessResult result = runInstallers(target, dependencies);
            if (result.exitCode() != 0) {
                return fail(skillId, versionId, hash, attempt, principal.id(), now, relativeRoot,
                    "依赖安装失败：" + result.output());
            }
            installMapper.update(
                versionId, hash, "succeeded", attempt, principal.id(), now, LocalDateTime.now(),
                relativeRoot, "依赖安装完成"
            );
        } catch (IOException exception) {
            return fail(skillId, versionId, hash, attempt, principal.id(), now, relativeRoot,
                "依赖缓存目录创建失败：" + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return fail(skillId, versionId, hash, attempt, principal.id(), now, relativeRoot, "依赖安装被中断");
        }
        return inspect(skillId, versionId);
    }

    /**
     * 处理{@code fail}并返回对应结果。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @param hash {@code hash}参数
     * @param attempt {@code attempt}参数
     * @param actorId 资源标识
     * @param requestedAt {@code requestedAt}参数
     * @param relativeRoot {@code relativeRoot}参数
     * @param message 待处理内容
     * @return 处理结果
     */
    private SkillDependencyInstallView fail(
        Long skillId,
        Long versionId,
        String hash,
        int attempt,
        Long actorId,
        LocalDateTime requestedAt,
        String relativeRoot,
        String message
    ) {
        installMapper.update(
            versionId, hash, "failed", attempt, actorId, requestedAt, LocalDateTime.now(),
            relativeRoot, trim(message)
        );
        return inspect(skillId, versionId);
    }

    /**
     * 执行{@code Installers}相关的处理流程。
     *
     * @param target {@code target}参数
     * @param dependencies {@code dependencies}参数
     * @return 处理结果
     * @throws IOException 当处理过程无法正常完成时抛出
     * @throws InterruptedException 当处理过程无法正常完成时抛出
     */
    private ProcessResult runInstallers(Path target, Map<String, List<String>> dependencies)
        throws IOException, InterruptedException {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        List<ProcessResult> results = new ArrayList<>();
        List<String> python = dependencies.getOrDefault("python", List.of());
        if (!python.isEmpty()) {
            Path pythonTarget = target.resolve("python").normalize();
            Files.createDirectories(pythonTarget);
            List<String> command = new ArrayList<>(List.of(
                pythonExecutable, "-m", "pip", "install", "--disable-pip-version-check",
                "--no-input", "--no-cache-dir", "--target", pythonTarget.toString()
            ));
            command.addAll(python);
            results.add(run(command, target));
        }
        List<String> node = dependencies.getOrDefault("node", List.of());
        if (!node.isEmpty()) {
            Path nodeTarget = target.resolve("node").normalize();
            Files.createDirectories(nodeTarget);
            List<String> command = new ArrayList<>(List.of(
                npmExecutable, "install", "--ignore-scripts", "--no-audit", "--no-fund",
                "--prefix", nodeTarget.toString()
            ));
            command.addAll(node);
            results.add(run(command, target));
        }
        for (ProcessResult result : results) {
            if (result.exitCode() != 0) {
                return result;
            }
        }
        return new ProcessResult(0, results.stream().map(ProcessResult::output).collect(java.util.stream.Collectors.joining("\n")));
    }

    /**
     * 执行{@code run}相关的处理流程。
     *
     * @param command 命令参数
     * @param directory 目录参数
     * @return 处理结果
     * @throws IOException 当处理过程无法正常完成时抛出
     * @throws InterruptedException 当处理过程无法正常完成时抛出
     */
    private ProcessResult run(List<String> command, Path directory)
        throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .start();
        StringBuilder output = new StringBuilder();
        Thread collector = Thread.ofVirtual().start(() -> {
            try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
                char[] buffer = new char[1024];
                int read;
                while ((read = reader.read(buffer)) >= 0 && output.length() < MAX_OUTPUT_BYTES) {
                    output.append(buffer, 0, Math.min(read, MAX_OUTPUT_BYTES - output.length()));
                }
            } catch (IOException ignored) {
                // The exit code remains authoritative; a bounded diagnostic is best effort.
            }
        });
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            collector.join(TimeUnit.SECONDS.toMillis(2));
            return new ProcessResult(124, trim(output + "\n安装超时"));
        }
        collector.join(TimeUnit.SECONDS.toMillis(2));
        return new ProcessResult(process.exitValue(), trim(output.toString()));
    }

    /**
     * 校验版本，并在条件不满足时终止处理。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @return 处理结果
     */
    private AgentSkillVersion requireVersion(Long skillId, Long versionId) {
        AgentSkillVersion version = catalogMapper.selectVersion(skillId, versionId);
        if (version == null) {
            throw new ServiceException("技能版本不存在", HttpStatus.NOT_FOUND);
        }
        return version;
    }

    /**
     * 处理{@code dependencies}并返回对应结果。
     *
     * @param version 版本参数
     * @return 处理结果
     */
    private Map<String, List<String>> dependencies(AgentSkillVersion version) {
        try {
            Map<String, Object> runtime = version.getRuntimeRequirementsJson() == null
                ? Map.of() : jsonMapper.readValue(version.getRuntimeRequirementsJson(), MAP_TYPE);
            return SkillDependencySpec.lists(runtime.get("dependencies"));
        } catch (RuntimeException exception) {
            throw new ServiceException("技能依赖声明无效，无法安装", HttpStatus.CONFLICT);
        }
    }

    /**
     * 处理{@code dependencyHash}并返回对应结果。
     *
     * @param dependencies {@code dependencies}参数
     * @return 处理结果
     */
    private String dependencyHash(Map<String, List<String>> dependencies) {
        try {
            return SkillDependencySpec.hash(dependencies, jsonMapper);
        } catch (RuntimeException exception) {
            throw new ServiceException("技能依赖声明无法生成哈希", HttpStatus.CONFLICT);
        }
    }

    /**
     * 处理{@code executable}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String executable(String value, String label) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0
            || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0
            || value.contains(" ") || value.contains("\t")) {
            throw new IllegalArgumentException(label + " executable must be a simple command name");
        }
        return value.strip();
    }

    /**
     * 处理{@code trim}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String trim(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_OUTPUT_BYTES ? value : value.substring(0, MAX_OUTPUT_BYTES);
    }

    /**
     * 封装{@code Process}相关的不可变数据。
     */
    private record ProcessResult(int exitCode, String output) {
    }
}
