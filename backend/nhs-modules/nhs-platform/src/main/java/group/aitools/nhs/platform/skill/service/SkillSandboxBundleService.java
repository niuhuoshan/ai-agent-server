package group.aitools.nhs.platform.skill.service;

import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.sandbox.persistence.row.SandboxJobRow;
import group.aitools.nhs.platform.sandbox.service.SandboxSkillManifest;
import group.aitools.nhs.platform.skill.domain.AgentSkillDependencyInstall;
import group.aitools.nhs.platform.skill.domain.AgentSkillFile;
import group.aitools.nhs.platform.skill.mapper.SkillDependencyInstallMapper;
import group.aitools.nhs.platform.skill.mapper.SkillFileMapper;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

/**
 * 负责技能沙箱Bundle相关的业务编排与领域规则处理。
 * Creates the authenticated, immutable Skill tarball consumed by a Sandbox Runner attempt. */
@Service
public class SkillSandboxBundleService {

    private static final long MAX_BYTES = 128L * 1024 * 1024;
    private static final int MAX_FILES = 4096;
    private static final String RESERVED = SkillDependencyRuntimeMountService.INJECTED_DIRECTORY;

    private final SkillFileMapper fileMapper;
    private final SkillDependencyInstallMapper dependencyMapper;
    private final JsonMapper jsonMapper;
    private final Path dependencyRoot;

    public SkillSandboxBundleService(
        SkillFileMapper fileMapper,
        SkillDependencyInstallMapper dependencyMapper,
        JsonMapper jsonMapper,
        @Value("${agent.skill.dependencies.root:./data/skill-dependencies}") String dependencyRoot
    ) {
        this.fileMapper = fileMapper;
        this.dependencyMapper = dependencyMapper;
        this.jsonMapper = jsonMapper;
        this.dependencyRoot = Path.of(dependencyRoot).toAbsolutePath().normalize();
    }

    /**
     * 处理{@code writeBundle}相关逻辑。
     *
     * @param job 作业参数
     * @param output {@code output}参数
     */
    public void writeBundle(SandboxJobRow job, OutputStream output) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (job == null || output == null) {
            throw unavailable("沙箱作业或输出流缺失");
        }
        SandboxSkillManifest.Normalized manifest = SandboxSkillManifest.fromJson(
            job.getSkillManifestJson(), jsonMapper
        );
        if (manifest.empty()) {
            throw unavailable("作业没有冻结 Skill");
        }
        if (job.getWorkspaceKey() == null || manifest.workspaceKey() == null
            || !job.getWorkspaceKey().equals(manifest.workspaceKey())) {
            throw unavailable("Skill manifest 与作业工作区不一致");
        }
        if (!manifestHashMatches(job, manifest)) {
            throw unavailable("Skill manifest 哈希不一致");
        }

        ArchiveBudget budget = new ArchiveBudget();
        // Build the complete archive before touching the HTTP response.  A missing file or
        // dependency must produce a normal failure, never a successful response containing a
        // half-written gzip stream.
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(archive)) {
            TarWriter tar = new TarWriter(gzip, budget);
            for (Map<String, Object> entry : manifest.entries()) {
                writeSkill(tar, entry, budget);
            }
            tar.finish();
        } catch (IOException exception) {
            throw new ServiceException("Skill 归档输出失败", HttpStatus.ERROR);
        }
        try {
            output.write(archive.toByteArray());
        } catch (IOException exception) {
            throw new ServiceException("Skill 归档输出失败", HttpStatus.ERROR);
        }
    }

    /**
     * 处理write技能相关逻辑。
     *
     * @param tar {@code tar}参数
     * @param entry {@code entry}参数
     * @param budget {@code budget}参数
     * @throws IOException 当处理过程无法正常完成时抛出
     */
    private void writeSkill(TarWriter tar, Map<String, Object> entry, ArchiveBudget budget)
        throws IOException {
        long skillId = positive(entry.get("skillId"), "Skill 资源 ID");
        long versionId = positive(entry.get("versionId"), "Skill 版本 ID");
        String key = text(entry.get("skillKey"), "Skill 标识");
        String expectedHash = text(entry.get("bundleHash"), "Skill 文件包哈希");
        List<AgentSkillFile> files = fileMapper.selectFiles(skillId, versionId);
        if (files == null || files.isEmpty()) {
            throw unavailable(key + " 文件包不存在");
        }
        validateFiles(key, expectedHash, files);
        for (AgentSkillFile file : files.stream()
            .filter(item -> item != null && "0".equals(item.getDelFlag()))
            .sorted(Comparator.comparing(AgentSkillFile::getPath))
            .toList()) {
            String archivePath = "skills/" + key + "/" + safeRelative(file.getPath());
            if ("directory".equals(file.getFileKind())) {
                tar.directory(archivePath);
            } else {
                tar.file(archivePath, bytes(file));
            }
        }
        writeDependencies(tar, budget, key, skillId, versionId, entry.get("runtimeRequirements"));
    }

    /**
 * 处理{@code manifestHashMatches}并返回对应结果。
 * Accept the one historical PostgreSQL JSONB text hash while all new rows use canonical JSON. */
    private boolean manifestHashMatches(
        SandboxJobRow job,
        SandboxSkillManifest.Normalized manifest
    ) {
        String stored = job.getSkillManifestHash();
        if (stored == null) {
            return false;
        }
        if (stored.equalsIgnoreCase(manifest.hash())) {
            return true;
        }
        return job.getSkillManifestJson() != null
            && stored.equalsIgnoreCase(ContentHashing.sha256(job.getSkillManifestJson()));
    }

    /**
     * 处理{@code writeDependencies}相关逻辑。
     *
     * @param tar {@code tar}参数
     * @param budget {@code budget}参数
     * @param skillKey 技能Key参数
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @param requirements {@code requirements}参数
     * @throws IOException 当处理过程无法正常完成时抛出
     */
    private void writeDependencies(
        TarWriter tar,
        ArchiveBudget budget,
        String skillKey,
        long skillId,
        long versionId,
        Object requirements
    ) throws IOException {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        Map<String, List<String>> dependencies;
        try {
            Object raw = requirements instanceof Map<?, ?> map ? map.get("dependencies") : null;
            dependencies = SkillDependencySpec.lists(raw);
        } catch (RuntimeException exception) {
            throw unavailable(skillKey + " 依赖声明无效");
        }
        if (dependencies.values().stream().allMatch(List::isEmpty)) {
            return;
        }
        String hash = SkillDependencySpec.hash(dependencies, jsonMapper);
        AgentSkillDependencyInstall state = dependencyMapper.select(versionId, hash);
        String expectedRoot = "skill-" + skillId + "/version-" + versionId + "/" + hash.substring(0, 16);
        if (state == null || !"succeeded".equals(state.getStatus())
            || !Long.valueOf(skillId).equals(state.getSkillId())
            || !Long.valueOf(versionId).equals(state.getVersionId())
            || !expectedRoot.equals(state.getInstallRoot())) {
            throw unavailable(skillKey + " 依赖缓存未就绪");
        }
        Path root = dependencyRoot.resolve(expectedRoot).normalize();
        if (!root.startsWith(dependencyRoot) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw unavailable(skillKey + " 依赖缓存路径无效");
        }
        rejectLinks(root);
        for (String type : List.of("python", "node")) {
            if (dependencies.getOrDefault(type, List.of()).isEmpty()) {
                continue;
            }
            Path source = root.resolve(type).normalize();
            if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                throw unavailable(skillKey + " 缺少 " + type + " 依赖缓存");
            }
            addTree(
                tar, budget, source,
                "skills/" + skillKey + "/" + RESERVED + "/" + type,
                source
            );
        }
    }

    /**
     * 创建并保存{@code Tree}。
     *
     * @param tar {@code tar}参数
     * @param budget {@code budget}参数
     * @param root {@code root}参数
     * @param archiveRoot {@code archiveRoot}参数
     * @param sourceRoot 数据源Root参数
     * @throws IOException 当处理过程无法正常完成时抛出
     */
    private void addTree(
        TarWriter tar,
        ArchiveBudget budget,
        Path root,
        String archiveRoot,
        Path sourceRoot
    )
        throws IOException {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        try (var stream = Files.walk(root).sorted()) {
            for (Path path : stream.toList()) {
                if (path.equals(root)) {
                    continue;
                }
                if (Files.isSymbolicLink(path)) {
                    throw unavailable("依赖缓存包含符号链接");
                }
                Path relative = sourceRoot.relativize(path);
                String archivePath = archiveRoot + "/" + safeRelative(relative.toString());
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    tar.directory(archivePath);
                } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    tar.file(archivePath, dependencyBytes(path, budget));
                } else {
                    throw unavailable("依赖缓存包含不支持的文件");
                }
            }
        }
    }

    /**
     * 处理{@code dependencyBytes}并返回对应结果。
     *
     * @param path {@code path}参数
     * @param budget {@code budget}参数
     * @return 处理结果
     * @throws IOException 当处理过程无法正常完成时抛出
     */
    private byte[] dependencyBytes(Path path, ArchiveBudget budget) throws IOException {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (Files.isSymbolicLink(path)) {
            throw unavailable("依赖缓存包含符号链接");
        }
        long expectedSize = Files.size(path);
        budget.ensureCanAdd(expectedSize);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(
            (int) Math.min(expectedSize, 64L * 1024)
        );
        long total = 0;
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                if (total > budget.remainingBytes() - read) {
                    throw unavailable("依赖缓存超过运行时上限");
                }
                bytes.write(buffer, 0, read);
                total += read;
            }
        }
        if (total != expectedSize) {
            throw unavailable("依赖缓存读取期间发生变化");
        }
        return bytes.toByteArray();
    }

    /**
     * 校验{@code Files}，并在条件不满足时终止处理。
     *
     * @param skillKey 技能Key参数
     * @param expectedHash {@code expectedHash}参数
     * @param files {@code files}参数
     */
    private void validateFiles(String skillKey, String expectedHash, List<AgentSkillFile> files) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (files.stream().anyMatch(java.util.Objects::isNull)) {
            throw unavailable(skillKey + " 文件记录无效");
        }
        List<AgentSkillFile> active = files.stream()
            .filter(item -> item != null && "0".equals(item.getDelFlag()))
            .toList();
        if (active.size() > 256) {
            throw unavailable(skillKey + " 文件数量超过上限");
        }
        Set<String> paths = new HashSet<>();
        Map<String, String> pathKinds = new java.util.HashMap<>();
        List<String> hashEntries = new ArrayList<>();
        boolean hasSkillMarkdown = false;
        long totalBytes = 0;
        for (AgentSkillFile file : active) {
            String path = safeRelative(file.getPath());
            if (!paths.add(path) || path.equals(RESERVED) || path.startsWith(RESERVED + "/")) {
                throw unavailable(skillKey + " 文件路径无效");
            }
            if (!"file".equals(file.getFileKind()) && !"directory".equals(file.getFileKind())) {
                throw unavailable(skillKey + " 文件类型无效");
            }
            if ("directory".equals(file.getFileKind())) {
                if (file.getSizeBytes() == null || file.getSizeBytes() != 0
                    || file.getContent() != null || file.getContentBytes() != null
                    || !ContentHashing.sha256("").equalsIgnoreCase(file.getContentHash())) {
                    throw unavailable(skillKey + " 目录记录无效");
                }
            } else {
                byte[] bytes = bytes(file);
                if (file.getSizeBytes() == null || file.getSizeBytes() != bytes.length
                    || bytes.length > 5 * 1024 * 1024
                    || !ContentHashing.sha256(bytes).equalsIgnoreCase(file.getContentHash())) {
                    throw unavailable(skillKey + " 文件校验失败");
                }
                totalBytes += bytes.length;
                if (totalBytes > 32L * 1024 * 1024) {
                    throw unavailable(skillKey + " 文件包超过运行时上限");
                }
            }
            pathKinds.put(path, file.getFileKind());
            hasSkillMarkdown |= "SKILL.md".equals(path) && "file".equals(file.getFileKind());
            hashEntries.add(path + "\n" + file.getFileKind() + "\n" + file.getContentHash());
        }
        // A tar archive cannot represent a file and one of its descendants at the same time.
        // Reject that malformed snapshot before writing any response bytes.
        for (String path : pathKinds.keySet()) {
            int slash = path.indexOf('/');
            while (slash > 0) {
                if ("file".equals(pathKinds.get(path.substring(0, slash)))) {
                    throw unavailable(skillKey + " 文件路径存在父子冲突");
                }
                slash = path.indexOf('/', slash + 1);
            }
        }
        if (!hasSkillMarkdown) {
            throw unavailable(skillKey + " 缺少 SKILL.md");
        }
        hashEntries.sort(String::compareTo);
        String actual = ContentHashing.sha256(String.join("\n", hashEntries));
        if (!actual.equalsIgnoreCase(expectedHash)) {
            throw unavailable(skillKey + " 文件包哈希不一致");
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
        throw unavailable("Skill 文件编码无效");
    }

    /**
     * 处理{@code safeRelative}并返回对应结果。
     *
     * @param raw {@code raw}参数
     * @return 处理结果
     */
    private String safeRelative(String raw) {
        if (raw == null || raw.isBlank() || raw.indexOf('\0') >= 0 || raw.indexOf('\\') >= 0) {
            throw unavailable("归档路径无效");
        }
        Path path = Path.of(raw).normalize();
        if (path.isAbsolute() || path.startsWith("..")) {
            throw unavailable("归档路径越界");
        }
        return path.toString().replace(java.io.File.separatorChar, '/');
    }

    /**
     * 处理{@code rejectLinks}相关逻辑。
     *
     * @param root {@code root}参数
     * @throws IOException 当处理过程无法正常完成时抛出
     */
    private void rejectLinks(Path root) throws IOException {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (Files.isSymbolicLink(root)) {
            throw unavailable("依赖缓存根目录不能是符号链接");
        }
        try (var stream = Files.walk(root)) {
            for (Path path : stream.toList()) {
                if (Files.isSymbolicLink(path)) {
                    throw unavailable("依赖缓存包含符号链接");
                }
            }
        }
    }

    /**
     * 处理{@code positive}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private long positive(Object value, String label) {
        if (!(value instanceof Number number) || number.longValue() <= 0
            || number.doubleValue() != number.longValue()) {
            throw unavailable(label + "无效");
        }
        return number.longValue();
    }

    /**
     * 处理{@code text}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String text(Object value, String label) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > 256) {
            throw unavailable(label + "无效");
        }
        return text.strip();
    }

    /**
     * 处理{@code unavailable}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException unavailable(String message) {
        return new ServiceException("Sandbox Skill bridge 不可用：" + message, 503);
    }

    /**
     * 表示{@code ArchiveBudget}相关的领域对象。
     */
    private static final class ArchiveBudget {
        private long bytes;
        private int files;

        /**
         * 创建并保存{@code add}。
         *
         * @param size 数量上限
         */
        private void add(long size) {
            ensureCanAdd(size);
            files++;
            bytes += size;
        }

        /**
         * 校验{@code CanAdd}，并在条件不满足时终止处理。
         *
         * @param size 数量上限
         */
        private void ensureCanAdd(long size) {
            if (size < 0 || bytes > MAX_BYTES - size || files >= MAX_FILES) {
                throw new ServiceException("Sandbox Skill 归档超过运行时上限", 503);
            }
        }

        /**
         * 处理{@code remainingBytes}并返回对应结果。
         *
         * @return 处理结果
         */
        private long remainingBytes() {
            return MAX_BYTES - bytes;
        }
    }

    /**
     * 表示{@code TarWriter}相关的领域对象。
     */
    private static final class TarWriter {
        private static final int BLOCK = 512;
        private final OutputStream output;
        private final ArchiveBudget budget;

        /**
         * 创建 {@code TarWriter} 实例并初始化所需依赖。
         *
         * @param output {@code output}参数
         * @param budget {@code budget}参数
         */
        private TarWriter(OutputStream output, ArchiveBudget budget) {
            this.output = output;
            this.budget = budget;
        }

        /**
         * 处理目录相关逻辑。
         *
         * @param path {@code path}参数
         * @throws IOException 当处理过程无法正常完成时抛出
         */
        private void directory(String path) throws IOException {
            budget.add(0);
            byte[] header = header(path.endsWith("/") ? path : path + "/", 0, (byte) '5');
            output.write(header);
        }

        /**
         * 处理文件相关逻辑。
         *
         * @param path {@code path}参数
         * @param bytes {@code bytes}参数
         * @throws IOException 当处理过程无法正常完成时抛出
         */
        private void file(String path, byte[] bytes) throws IOException {
            if (bytes == null) throw new IOException("null archive bytes");
            budget.add(bytes.length);
            output.write(header(path, bytes.length, (byte) '0'));
            output.write(bytes);
            int padding = (int) ((BLOCK - (bytes.length % BLOCK)) % BLOCK);
            if (padding > 0) output.write(new byte[padding]);
        }

        /**
         * 处理{@code finish}相关逻辑。
         *
         * @throws IOException 当处理过程无法正常完成时抛出
         */
        private void finish() throws IOException {
            output.write(new byte[BLOCK * 2]);
            output.flush();
        }

        /**
         * 处理{@code header}并返回对应结果。
         *
         * @param path {@code path}参数
         * @param size 数量上限
         * @param type 业务类型
         * @return 处理结果
         */
        private byte[] header(String path, long size, byte type) {
            // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
            byte[] header = new byte[BLOCK];
            byte[] name = path.getBytes(StandardCharsets.UTF_8);
            if (name.length > 255) throw new ServiceException("归档路径过长", 503);
            if (name.length <= 100) {
                System.arraycopy(name, 0, header, 0, name.length);
            } else {
                int split = -1;
                for (int candidate = path.indexOf('/'); candidate >= 0;
                     candidate = path.indexOf('/', candidate + 1)) {
                    byte[] prefix = path.substring(0, candidate).getBytes(StandardCharsets.UTF_8);
                    byte[] leaf = path.substring(candidate + 1).getBytes(StandardCharsets.UTF_8);
                    if (prefix.length <= 155 && leaf.length <= 100) {
                        split = candidate;
                    }
                }
                if (split <= 0) {
                    throw new ServiceException("归档路径过长", 503);
                }
                byte[] prefix = path.substring(0, split).getBytes(StandardCharsets.UTF_8);
                byte[] leaf = path.substring(split + 1).getBytes(StandardCharsets.UTF_8);
                System.arraycopy(leaf, 0, header, 0, leaf.length);
                System.arraycopy(prefix, 0, header, 345, prefix.length);
            }
            writeAscii(header, 100, 8, "0000777");
            writeAscii(header, 108, 8, "0000000");
            writeAscii(header, 116, 8, "0000000");
            writeOctal(header, 124, 12, size);
            writeOctal(header, 136, 12, 0);
            header[156] = type;
            byte[] magic = "ustar\00000".getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(magic, 0, header, 257, magic.length);
            for (int i = 148; i < 156; i++) header[i] = (byte) ' ';
            long checksum = 0;
            for (byte value : header) checksum += value & 0xff;
            writeAscii(header, 148, 8, String.format("%06o ", checksum));
            return header;
        }

        /**
         * 处理{@code writeOctal}相关逻辑。
         *
         * @param target {@code target}参数
         * @param offset 起始位置或序号
         * @param length {@code length}参数
         * @param value {@code value}参数
         */
        private void writeOctal(byte[] target, int offset, int length, long value) {
            writeAscii(target, offset, length, String.format("%0" + (length - 1) + "o", value));
        }

        /**
         * 处理{@code writeAscii}相关逻辑。
         *
         * @param target {@code target}参数
         * @param offset 起始位置或序号
         * @param length {@code length}参数
         * @param value {@code value}参数
         */
        private void writeAscii(byte[] target, int offset, int length, String value) {
            byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
            int copy = Math.min(bytes.length, length);
            System.arraycopy(bytes, 0, target, offset, copy);
        }
    }
}
