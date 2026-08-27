package group.aitools.nhs.platform.skill.service;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.skill.domain.AgentSkillFile;
import group.aitools.nhs.platform.skill.mapper.SkillFileMapper;
import group.aitools.nhs.platform.skill.web.SkillFileView;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 负责技能文件Bundle相关的业务编排与领域规则处理。
 * Secure text bundle storage used by platform and personal Skill APIs. */
@Service
public class SkillFileBundleService {

    private static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_ARCHIVE_FILES = 256;
    private static final long MAX_ARCHIVE_BYTES = 32L * 1024 * 1024;

    private final CurrentPrincipalProvider principalProvider;
    private final PlatformIdGenerator idGenerator;
    private final SkillFileMapper mapper;
    private final SkillCatalogService catalogService;

    public SkillFileBundleService(
        CurrentPrincipalProvider principalProvider,
        PlatformIdGenerator idGenerator,
        SkillFileMapper mapper,
        SkillCatalogService catalogService
    ) {
        this.principalProvider = principalProvider;
        this.idGenerator = idGenerator;
        this.mapper = mapper;
        this.catalogService = catalogService;
    }

    /**
     * 查询{@code list}列表。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @return 符合条件的数据集合
     */
    public List<SkillFileView> list(Long skillId, Long versionId) {
        catalogService.requireFileAccess(skillId, versionId, false);
        return mapper.selectFiles(skillId, versionId).stream().map(file -> SkillFileView.from(file, false)).toList();
    }

    /**
     * 获取{@code get}。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @param rawPath {@code rawPath}参数
     * @return 处理结果
     */
    public SkillFileView get(Long skillId, Long versionId, String rawPath) {
        catalogService.requireFileAccess(skillId, versionId, false);
        AgentSkillFile file = mapper.selectFile(skillId, versionId, path(rawPath));
        if (file == null) throw new ServiceException("技能文件不存在", HttpStatus.NOT_FOUND);
        return SkillFileView.from(file, true);
    }

    /**
     * 处理{@code put}并返回对应结果。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @param rawPath {@code rawPath}参数
     * @param content 待处理内容
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public SkillFileView put(Long skillId, Long versionId, String rawPath, String content) {
        catalogService.requireFileAccess(skillId, versionId, true);
        String normalizedPath = path(rawPath);
        String value = content == null ? "" : content;
        if ("SKILL.md".equals(normalizedPath)) {
            value = catalogService.synchronizeSkillMarkdown(skillId, versionId, value);
        }
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_FILE_BYTES) throw new ServiceException("技能文件超过 5MB 限制", HttpStatus.BAD_REQUEST);
        CurrentPrincipal actor = principalProvider.currentPrincipal();
        AgentSkillFile file = new AgentSkillFile();
        file.setId(idGenerator.nextId());
        file.setSkillId(skillId);
        file.setVersionId(versionId);
        file.setPath(normalizedPath);
        file.setFileKind("file");
        file.setContent(value);
        file.setContentBytes(null);
        file.setContentEncoding("utf8");
        file.setContentHash(ContentHashing.sha256(value));
        file.setSizeBytes(bytes);
        file.setCreateBy(actor.id());
        file.setCreateTime(LocalDateTime.now());
        file.setDelFlag("0");
        mapper.upsert(file);
        mapper.refreshBundleHash(skillId, versionId, bundleHash(skillId, versionId));
        return SkillFileView.from(mapper.selectFile(skillId, versionId, normalizedPath), true);
    }

    /**
     * 处理{@code putBytes}并返回对应结果。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @param rawPath {@code rawPath}参数
     * @param bytes {@code bytes}参数
     * @return 处理结果
     */
    public SkillFileView putBytes(Long skillId, Long versionId, String rawPath, byte[] bytes) {
        if (bytes == null || bytes.length > MAX_FILE_BYTES) {
            throw new ServiceException("技能文件为空或超过 5MB 限制", HttpStatus.BAD_REQUEST);
        }
        String content = new String(bytes, StandardCharsets.UTF_8);
        if (Arrays.equals(bytes, content.getBytes(StandardCharsets.UTF_8))) {
            return put(skillId, versionId, rawPath, content);
        }
        return putBinary(skillId, versionId, rawPath, bytes);
    }

    /**
     * 处理{@code putBinary}并返回对应结果。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @param rawPath {@code rawPath}参数
     * @param bytes {@code bytes}参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public SkillFileView putBinary(Long skillId, Long versionId, String rawPath, byte[] bytes) {
        catalogService.requireFileAccess(skillId, versionId, true);
        if (bytes == null || bytes.length > MAX_FILE_BYTES) {
            throw new ServiceException("二进制技能文件为空或超过 5MB 限制", HttpStatus.BAD_REQUEST);
        }
        String normalizedPath = path(rawPath);
        CurrentPrincipal actor = principalProvider.currentPrincipal();
        AgentSkillFile file = new AgentSkillFile();
        file.setId(idGenerator.nextId());
        file.setSkillId(skillId);
        file.setVersionId(versionId);
        file.setPath(normalizedPath);
        file.setFileKind("file");
        file.setContent(null);
        file.setContentBytes(bytes.clone());
        file.setContentEncoding("binary");
        file.setContentHash(ContentHashing.sha256(bytes));
        file.setSizeBytes(bytes.length);
        file.setCreateBy(actor.id());
        file.setCreateTime(LocalDateTime.now());
        file.setDelFlag("0");
        mapper.upsert(file);
        mapper.refreshBundleHash(skillId, versionId, bundleHash(skillId, versionId));
        return SkillFileView.from(mapper.selectFile(skillId, versionId, normalizedPath), true);
    }

    /**
     * 创建并保存目录。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @param rawPath {@code rawPath}参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public SkillFileView createDirectory(Long skillId, Long versionId, String rawPath) {
        catalogService.requireFileAccess(skillId, versionId, true);
        String normalizedPath = path(rawPath);
        AgentSkillFile file = new AgentSkillFile();
        CurrentPrincipal actor = principalProvider.currentPrincipal();
        LocalDateTime now = LocalDateTime.now();
        file.setId(idGenerator.nextId());
        file.setSkillId(skillId);
        file.setVersionId(versionId);
        file.setPath(normalizedPath);
        file.setFileKind("directory");
        file.setContent(null);
        file.setContentBytes(null);
        file.setContentEncoding("utf8");
        file.setContentHash(ContentHashing.sha256(""));
        file.setSizeBytes(0);
        file.setCreateBy(actor.id());
        file.setCreateTime(now);
        file.setDelFlag("0");
        mapper.upsert(file);
        mapper.refreshBundleHash(skillId, versionId, bundleHash(skillId, versionId));
        return SkillFileView.from(file, false);
    }

    /**
     * 处理导入Archive并返回对应结果。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @param archive {@code archive}参数
     * @return 符合条件的数据集合
     */
    @Transactional(rollbackFor = Exception.class)
    public List<SkillFileView> importArchive(Long skillId, Long versionId, byte[] archive) {
        return importArchive(skillId, versionId, archive, null, null);
    }

    /**
     * 处理导入Archive并返回对应结果。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @param archive {@code archive}参数
     * @param rawPrefix {@code rawPrefix}参数
     * @return 符合条件的数据集合
     */
    @Transactional(rollbackFor = Exception.class)
    public List<SkillFileView> importArchive(Long skillId, Long versionId, byte[] archive, String rawPrefix) {
        return importArchive(skillId, versionId, archive, rawPrefix, null);
    }

    /**
     * 处理导入Archive并返回对应结果。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @param archive {@code archive}参数
     * @param rawPrefix {@code rawPrefix}参数
     * @param archiveName 名称
     * @return 符合条件的数据集合
     */
    @Transactional(rollbackFor = Exception.class)
    public List<SkillFileView> importArchive(
        Long skillId,
        Long versionId,
        byte[] archive,
        String rawPrefix,
        String archiveName
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        catalogService.requireFileAccess(skillId, versionId, true);
        String prefix = rawPrefix == null || rawPrefix.isBlank() ? null : path(rawPrefix);
        if (archive == null || archive.length == 0 || archive.length > MAX_ARCHIVE_BYTES) {
            throw new ServiceException("技能压缩包为空或超过 32MB", HttpStatus.BAD_REQUEST);
        }
        ArchiveImportState state = new ArchiveImportState();
        try {
            ArchiveFormat format = archiveFormat(archive, archiveName);
            if (format == ArchiveFormat.ZIP) {
                importZip(skillId, versionId, archive, prefix, state);
            } else if (format == ArchiveFormat.TAR_GZIP) {
                try (InputStream compressed = new GzipCompressorInputStream(new ByteArrayInputStream(archive));
                     TarArchiveInputStream input = new TarArchiveInputStream(compressed)) {
                    importTar(skillId, versionId, input, prefix, state);
                }
            } else {
                try (TarArchiveInputStream input = new TarArchiveInputStream(new ByteArrayInputStream(archive))) {
                    importTar(skillId, versionId, input, prefix, state);
                }
            }
        } catch (ServiceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ServiceException("技能压缩包无法读取", HttpStatus.BAD_REQUEST);
        }
        if (state.written == 0) {
            throw new ServiceException("技能压缩包不包含文件", HttpStatus.BAD_REQUEST);
        }
        return list(skillId, versionId);
    }

    /**
     * 处理导入Zip相关逻辑。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @param archive {@code archive}参数
     * @param prefix {@code prefix}参数
     * @param state {@code state}参数
     * @throws IOException 当处理过程无法正常完成时抛出
     */
    private void importZip(
        Long skillId,
        Long versionId,
        byte[] archive,
        String prefix,
        ArchiveImportState state
    ) throws IOException {
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                importEntry(skillId, versionId, entry.getName(), entry.isDirectory(), input, prefix, state);
            }
        }
    }

    /**
     * 处理导入Tar相关逻辑。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @param input {@code input}参数
     * @param prefix {@code prefix}参数
     * @param state {@code state}参数
     * @throws IOException 当处理过程无法正常完成时抛出
     */
    private void importTar(
        Long skillId,
        Long versionId,
        TarArchiveInputStream input,
        String prefix,
        ArchiveImportState state
    ) throws IOException {
        TarArchiveEntry entry;
        while ((entry = input.getNextTarEntry()) != null) {
            if (entry.isSymbolicLink() || entry.isLink()) {
                throw new ServiceException("压缩包不允许包含符号链接或硬链接", HttpStatus.BAD_REQUEST);
            }
            importEntry(skillId, versionId, entry.getName(), entry.isDirectory(), input, prefix, state);
        }
    }

    /**
     * 处理导入Entry相关逻辑。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @param rawName 名称
     * @param directory 目录参数
     * @param input {@code input}参数
     * @param prefix {@code prefix}参数
     * @param state {@code state}参数
     * @throws IOException 当处理过程无法正常完成时抛出
     */
    private void importEntry(
        Long skillId,
        Long versionId,
        String rawName,
        boolean directory,
        InputStream input,
        String prefix,
        ArchiveImportState state
    ) throws IOException {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (++state.entries > MAX_ARCHIVE_FILES) {
            throw new ServiceException("压缩包文件数超过限制", HttpStatus.BAD_REQUEST);
        }
        String normalized = archivePath(rawName);
        if (prefix != null) normalized = path(prefix + "/" + normalized);
        if (!state.seenPaths.add(normalized)) {
            throw new ServiceException("压缩包包含重复文件路径", HttpStatus.BAD_REQUEST);
        }
        if (directory) return;
        byte[] content = readEntry(input, state);
        putBytes(skillId, versionId, normalized, content);
        state.written++;
    }

    /**
     * 处理{@code readEntry}并返回对应结果。
     *
     * @param input {@code input}参数
     * @param state {@code state}参数
     * @return 处理结果
     * @throws IOException 当处理过程无法正常完成时抛出
     */
    private byte[] readEntry(InputStream input, ArchiveImportState state) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) continue;
            state.total += read;
            if (state.total > MAX_ARCHIVE_BYTES || output.size() + read > MAX_FILE_BYTES) {
                throw new ServiceException("压缩包解压内容超过限制", HttpStatus.BAD_REQUEST);
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    /**
     * 处理{@code archiveFormat}并返回对应结果。
     *
     * @param archive {@code archive}参数
     * @param archiveName 名称
     * @return 处理结果
     */
    private ArchiveFormat archiveFormat(byte[] archive, String archiveName) {
        if (archive.length >= 4 && archive[0] == 'P' && archive[1] == 'K'
            && archive[2] == 3 && archive[3] == 4) {
            return ArchiveFormat.ZIP;
        }
        String lowerName = archiveName == null ? "" : archiveName.toLowerCase(Locale.ROOT);
        boolean gzip = archive.length >= 2 && (archive[0] & 0xff) == 0x1f && (archive[1] & 0xff) == 0x8b;
        if (gzip || lowerName.endsWith(".tar.gz") || lowerName.endsWith(".tgz")) return ArchiveFormat.TAR_GZIP;
        boolean tar = archive.length > 262 && "ustar".equals(new String(archive, 257, 5, StandardCharsets.US_ASCII));
        if (tar || lowerName.endsWith(".tar")) return ArchiveFormat.TAR;
        throw new ServiceException("仅支持 ZIP、TAR 或 TAR.GZ 技能压缩包", HttpStatus.BAD_REQUEST);
    }

    /**
     * 定义{@code ArchiveFormat}相关的可选值。
     */
    private enum ArchiveFormat {
        ZIP, TAR, TAR_GZIP
    }

    /**
     * 表示Archive导入State相关的领域对象。
     */
    private static final class ArchiveImportState {
        private int entries;
        private int written;
        private long total;
        private final Set<String> seenPaths = new HashSet<>();
    }

    /**
     * 处理导出Archive并返回对应结果。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @return 处理结果
     */
    @Transactional(readOnly = true)
    public byte[] exportArchive(Long skillId, Long versionId) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        catalogService.requireFileAccess(skillId, versionId, false);
        List<AgentSkillFile> files = mapper.selectFiles(skillId, versionId);
        if (files.isEmpty()) throw new ServiceException("技能版本不包含文件", HttpStatus.NOT_FOUND);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            long total = 0;
            for (AgentSkillFile file : files) {
                String normalized = path(file.getPath());
                String entryName = "directory".equals(file.getFileKind()) && !normalized.endsWith("/")
                    ? normalized + "/" : normalized;
                output.putNextEntry(new ZipEntry(entryName));
                if (!"directory".equals(file.getFileKind())) {
                    byte[] content = file.getContentBytes() != null
                        ? file.getContentBytes() : (file.getContent() == null ? new byte[0]
                        : file.getContent().getBytes(StandardCharsets.UTF_8));
                    total += content.length;
                    if (total > MAX_ARCHIVE_BYTES) {
                        throw new ServiceException("技能归档超过 32MB 限制", HttpStatus.BAD_REQUEST);
                    }
                    output.write(content);
                }
                output.closeEntry();
            }
        } catch (IOException exception) {
            throw new ServiceException("技能归档生成失败", HttpStatus.ERROR);
        }
        if (bytes.size() > MAX_ARCHIVE_BYTES) {
            throw new ServiceException("技能归档超过 32MB 限制", HttpStatus.BAD_REQUEST);
        }
        return bytes.toByteArray();
    }

    /**
     * 删除{@code delete}。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @param rawPath {@code rawPath}参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long skillId, Long versionId, String rawPath) {
        catalogService.requireFileAccess(skillId, versionId, true);
        String normalized = path(rawPath);
        if (normalized.equalsIgnoreCase("SKILL.md")) {
            throw new ServiceException("禁止删除核心规范文件 SKILL.md", HttpStatus.BAD_REQUEST);
        }
        CurrentPrincipal actor = principalProvider.currentPrincipal();
        if (mapper.softDeleteTree(skillId, versionId, normalized, actor.id(), LocalDateTime.now()) < 1) {
            throw new ServiceException("技能文件不存在", HttpStatus.NOT_FOUND);
        }
        mapper.refreshBundleHash(skillId, versionId, bundleHash(skillId, versionId));
    }

    /**
     * 处理{@code bundleHash}并返回对应结果。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @return 处理结果
     */
    private String bundleHash(Long skillId, Long versionId) {
        String material = mapper.selectFiles(skillId, versionId).stream()
            .filter(file -> "0".equals(file.getDelFlag()))
            .sorted(java.util.Comparator.comparing(AgentSkillFile::getPath))
            .map(file -> file.getPath() + "\n" + file.getFileKind() + "\n" + file.getContentHash())
            .collect(java.util.stream.Collectors.joining("\n"));
        return ContentHashing.sha256(material);
    }

    /**
     * 处理{@code path}并返回对应结果。
     *
     * @param raw {@code raw}参数
     * @return 处理结果
     */
    private String path(String raw) {
        return path(raw, false);
    }

    /**
     * 处理{@code archivePath}并返回对应结果。
     *
     * @param raw {@code raw}参数
     * @return 处理结果
     */
    private String archivePath(String raw) {
        return path(raw, true);
    }

    /**
     * 处理{@code path}并返回对应结果。
     *
     * @param raw {@code raw}参数
     * @param archive {@code archive}参数
     * @return 处理结果
     */
    private String path(String raw, boolean archive) {
        String value = raw == null ? "" : raw.strip().replace('\\', '/');
        if (archive && (value.startsWith("/") || value.startsWith("~") || value.matches("^[A-Za-z]:.*"))) {
            throw new ServiceException("压缩包包含绝对路径", HttpStatus.BAD_REQUEST);
        }
        while (value.startsWith("/")) value = value.substring(1);
        if (value.isBlank() || value.length() > 512 || value.indexOf('\0') >= 0
            || value.startsWith(".") || value.contains("../") || value.endsWith("/..")
            || value.contains(":") || value.chars().anyMatch(character -> character < 32)) {
            throw new ServiceException("技能文件路径无效", HttpStatus.BAD_REQUEST);
        }
        return value;
    }
}
