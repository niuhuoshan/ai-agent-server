package group.aitools.nhs.platform.skill.service;

import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.BusinessRelation;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.nhs.service.NhsV1OperationAuditService;
import group.aitools.nhs.platform.notification.service.NotificationApplicationService;
import group.aitools.nhs.platform.notification.service.NotificationMessage;
import group.aitools.nhs.platform.notification.service.NotificationRecipient;
import group.aitools.nhs.platform.skill.domain.AgentSkill;
import group.aitools.nhs.platform.skill.domain.AgentSkillFile;
import group.aitools.nhs.platform.skill.domain.AgentSkillPublication;
import group.aitools.nhs.platform.skill.domain.AgentSkillPublicationFile;
import group.aitools.nhs.platform.skill.domain.AgentSkillPublicationVersion;
import group.aitools.nhs.platform.skill.domain.AgentSkillVersion;
import group.aitools.nhs.platform.skill.mapper.SkillCatalogMapper;
import group.aitools.nhs.platform.skill.mapper.SkillFileMapper;
import group.aitools.nhs.platform.skill.mapper.SkillPublicationMapper;
import group.aitools.nhs.platform.skill.web.SkillPublicationFileNode;
import group.aitools.nhs.platform.skill.web.SkillPublicationView;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 负责技能Publication相关的业务编排与领域规则处理。
 * Durable personal Skill publication, review and system-copy materialization. */
@Service
public class SkillPublicationService {

    private static final int MAX_FILES = 256;
    private static final long MAX_TOTAL_BYTES = 32L * 1024 * 1024;

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final PlatformIdGenerator idGenerator;
    private final SkillCatalogMapper catalogMapper;
    private final SkillFileMapper fileMapper;
    private final SkillPublicationMapper publicationMapper;
    private final NotificationApplicationService notificationService;
    private final NhsV1OperationAuditService auditService;

    public SkillPublicationService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        PlatformIdGenerator idGenerator,
        SkillCatalogMapper catalogMapper,
        SkillFileMapper fileMapper,
        SkillPublicationMapper publicationMapper,
        NotificationApplicationService notificationService,
        NhsV1OperationAuditService auditService
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.idGenerator = idGenerator;
        this.catalogMapper = catalogMapper;
        this.fileMapper = fileMapper;
        this.publicationMapper = publicationMapper;
        this.notificationService = notificationService;
        this.auditService = auditService;
    }

    /**
     * 处理{@code submit}并返回对应结果。
     *
     * @param skillId 资源标识
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public SkillPublicationView submit(Long skillId) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        CurrentPrincipal actor = requireHuman();
        AgentSkill source = requireOwnedPersonalSkill(skillId, actor);
        if ("archived".equals(source.getStatus())) throw conflict("已归档个人技能不能发布");
        require(actor, source.getId(), source.getSkillKey(), "publish", Set.of(BusinessRelation.OWNER));

        Long sourceVersionId = catalogMapper.selectLatestVersionId(skillId);
        if (sourceVersionId == null) {
            throw notFound("个人技能版本不存在");
        }
        AgentSkillVersion sourceVersion = catalogMapper.selectVersion(skillId, sourceVersionId);
        if (sourceVersion == null || "archived".equals(sourceVersion.getStatus())) {
            throw conflict("个人技能没有可提交的版本");
        }
        List<AgentSkillFile> sourceFiles = fileMapper.selectFiles(skillId, sourceVersionId);
        SnapshotStats stats = validateSourceSnapshot(sourceVersion, sourceFiles);

        LocalDateTime now = LocalDateTime.now();
        AgentSkillPublication publication = publicationMapper.selectBySourceSkill(skillId);
        if (publication == null) {
            publication = new AgentSkillPublication();
            publication.setId(idGenerator.nextId());
            publication.setSourceSkillId(skillId);
            publication.setSourceOwnerId(actor.id());
            publication.setStatus("unpublished");
            publication.setCreatedAt(now);
            publication.setUpdatedAt(now);
            try {
                publicationMapper.insertPublication(publication);
            } catch (DuplicateKeyException ignored) {
                publication = publicationMapper.selectBySourceSkill(skillId);
            }
        }
        if (publication == null || !actor.id().equals(publication.getSourceOwnerId())) {
            throw new ServiceException("无权提交该个人技能", HttpStatus.FORBIDDEN);
        }
        publicationMapper.lockBySourceSkill(skillId);
        publication = publicationMapper.selectBySourceSkill(skillId);
        if (publicationMapper.selectPendingVersion(publication.getId()) != null) {
            throw conflict("该个人技能已有待审核发布申请");
        }

        AgentSkillPublicationVersion snapshot = new AgentSkillPublicationVersion();
        snapshot.setId(idGenerator.nextId());
        snapshot.setPublicationId(publication.getId());
        snapshot.setVersionNo(publicationMapper.selectNextVersionNo(publication.getId()));
        snapshot.setSourceSkillVersionId(sourceVersionId);
        snapshot.setSourceSkillKeySnapshot(source.getSkillKey());
        snapshot.setNameSnapshot(source.getName());
        snapshot.setDescriptionSnapshot(source.getDescription());
        snapshot.setContentSnapshot(sourceVersion.getContent());
        snapshot.setManifestJson(sourceVersion.getManifestJson());
        snapshot.setRuntimeRequirementsJson(sourceVersion.getRuntimeRequirementsJson());
        snapshot.setStatus("pending");
        snapshot.setContentHash(sourceVersion.getContentHash());
        snapshot.setFileBundleHash(stats.bundleHash());
        snapshot.setFileCount(stats.fileCount());
        snapshot.setTotalSizeBytes(stats.totalSize());
        snapshot.setSubmittedBy(actor.id());
        snapshot.setSubmittedAt(now);
        publicationMapper.insertVersion(snapshot);

        for (AgentSkillFile sourceFile : sourceFiles) {
            publicationMapper.insertFile(snapshotFile(snapshot.getId(), sourceFile));
        }
        if (publicationMapper.updateStatus(publication.getId(), "pending", now) != 1) {
            throw conflict("技能发布状态发生并发变化");
        }
        publication.setStatus("pending");
        publication.setUpdatedAt(now);

        notificationService.publishApprovalAudience(new NotificationMessage(
            "skill-publication:pending:" + snapshot.getId(),
            "approval",
            "info",
            "有新的技能发布申请待审核",
            "用户 " + actor.username() + " 提交了技能「" + source.getName() + "」v" + snapshot.getVersionNo(),
            "skill_publication",
            snapshot.getId()
        ));
        auditService.recordCurrent(
            "skill.publication.submit", "skill_publication", snapshot.getId(),
            "allow", "owner_submit", "skill_id=" + skillId + ",version=" + snapshot.getVersionNo()
        );
        return view(publication, snapshot, false);
    }

    /**
     * 处理{@code status}并返回对应结果。
     *
     * @param skillId 资源标识
     * @return 处理结果
     */
    public SkillPublicationView status(Long skillId) {
        CurrentPrincipal actor = requireHuman();
        AgentSkill source = requireOwnedPersonalSkill(skillId, actor);
        require(actor, source.getId(), source.getSkillKey(), "view", Set.of(BusinessRelation.OWNER));
        AgentSkillPublication publication = publicationMapper.selectBySourceSkill(skillId);
        if (publication == null) {
            return unpublished(source);
        }
        return view(publication, publicationMapper.selectLatestVersion(publication.getId()), false);
    }

    /**
     * 处理{@code withdraw}并返回对应结果。
     *
     * @param skillId 资源标识
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public SkillPublicationView withdraw(Long skillId) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal actor = requireHuman();
        AgentSkill source = requireOwnedPersonalSkill(skillId, actor);
        if ("archived".equals(source.getStatus())) throw conflict("已归档个人技能不能撤回发布");
        require(actor, source.getId(), source.getSkillKey(), "publish", Set.of(BusinessRelation.OWNER));
        AgentSkillPublication publication = publicationMapper.selectBySourceSkill(skillId);
        if (publication == null || !actor.id().equals(publication.getSourceOwnerId())) {
            throw notFound("技能发布申请不存在");
        }
        publicationMapper.lockBySourceSkill(skillId);
        AgentSkillPublicationVersion pending = publicationMapper.selectPendingVersion(publication.getId());
        if (pending == null) {
            throw conflict("当前没有可撤回的待审核申请");
        }
        LocalDateTime now = LocalDateTime.now();
        if (publicationMapper.withdrawVersion(pending.getId(), actor.id(), now) != 1
            || publicationMapper.updateStatus(publication.getId(), "withdrawn", now) != 1) {
            throw conflict("技能发布申请状态发生并发变化");
        }
        pending.setStatus("withdrawn");
        pending.setWithdrawnBy(actor.id());
        pending.setWithdrawnAt(now);
        publication.setStatus("withdrawn");
        publication.setUpdatedAt(now);
        auditService.recordCurrent(
            "skill.publication.withdraw", "skill_publication", pending.getId(),
            "allow", "owner_withdraw", "skill_id=" + skillId + ",version=" + pending.getVersionNo()
        );
        return view(publication, pending, false);
    }

    /**
     * 处理{@code pending}并返回对应结果。
     *
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<SkillPublicationView> pending(int limit) {
        CurrentPrincipal actor = requireHuman();
        return publicationMapper.selectPendingVersions(Math.max(1, Math.min(limit, 500))).stream()
            .filter(version -> canReview(actor, version, "approve") || canReview(actor, version, "reject"))
            .map(version -> view(requirePublication(version.getPublicationId()), version, false))
            .toList();
    }

    /**
     * 处理{@code detail}并返回对应结果。
     *
     * @param versionId 资源标识
     * @return 处理结果
     */
    public SkillPublicationView detail(Long versionId) {
        CurrentPrincipal actor = requireHuman();
        AgentSkillPublicationVersion version = requireVersion(versionId);
        AgentSkillPublication publication = requirePublication(version.getPublicationId());
        if (!canReview(actor, publication, version, "approve")
            && !canReview(actor, publication, version, "reject")) {
            requireReviewer(actor, publication, version, "approve");
        }
        return view(publication, version, true);
    }

    /**
     * 处理{@code approve}并返回对应结果。
     *
     * @param versionId 资源标识
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public SkillPublicationView approve(Long versionId) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal actor = requireHuman();
        AgentSkillPublicationVersion candidate = requireVersion(versionId);
        AgentSkillPublication publication = requirePublication(candidate.getPublicationId());
        requireReviewer(actor, publication, candidate, "approve");
        publicationMapper.lockVersion(versionId);
        candidate = requireVersion(versionId);
        publication = requirePublication(candidate.getPublicationId());
        if ("approved".equals(candidate.getStatus())) {
            return view(publication, candidate, true);
        }
        if (!"pending".equals(candidate.getStatus())) {
            throw conflict("只有待审核的技能发布申请可以通过");
        }

        List<AgentSkillPublicationFile> files = publicationMapper.selectFiles(versionId);
        validateSnapshot(candidate, files);
        LocalDateTime now = LocalDateTime.now();
        MaterializedSkill materialized = materialize(publication, candidate, files, actor.id(), now);
        publicationMapper.supersedeApproved(publication.getId(), versionId);
        if (publicationMapper.approveVersion(
                versionId, actor.id(), now, materialized.skillId(), materialized.versionId()
            ) != 1
            || publicationMapper.markPublished(
                publication.getId(), materialized.skillId(), candidate.getVersionNo(), now
            ) != 1) {
            throw conflict("技能发布申请状态发生并发变化");
        }
        candidate.setStatus("approved");
        candidate.setReviewedBy(actor.id());
        candidate.setReviewedAt(now);
        candidate.setPublishedSystemSkillId(materialized.skillId());
        candidate.setPublishedSystemVersionId(materialized.versionId());
        publication.setSystemSkillId(materialized.skillId());
        publication.setCurrentPublicVersionNo(candidate.getVersionNo());
        publication.setStatus("published");
        publication.setUpdatedAt(now);

        notifyOwner(
            publication, candidate, "approved", "success", "技能发布申请已通过",
            "技能「" + candidate.getNameSnapshot() + "」已发布为系统技能"
        );
        auditService.recordCurrent(
            "skill.publication.approve", "skill_publication", versionId,
            "allow", "review_approved", "system_skill_id=" + materialized.skillId()
        );
        return view(publication, candidate, true);
    }

    /**
     * 处理{@code reject}并返回对应结果。
     *
     * @param versionId 资源标识
     * @param rawComment {@code rawComment}参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public SkillPublicationView reject(Long versionId, String rawComment) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal actor = requireHuman();
        AgentSkillPublicationVersion candidate = requireVersion(versionId);
        AgentSkillPublication publication = requirePublication(candidate.getPublicationId());
        requireReviewer(actor, publication, candidate, "reject");
        publicationMapper.lockVersion(versionId);
        candidate = requireVersion(versionId);
        if (!"pending".equals(candidate.getStatus())) {
            throw conflict("只有待审核的技能发布申请可以驳回");
        }
        String comment = rawComment == null ? "" : rawComment.strip();
        if (comment.isBlank() || comment.length() > 2000 || comment.indexOf('\0') >= 0) {
            throw new ServiceException("驳回原因不能为空且不能超过 2000 字", HttpStatus.BAD_REQUEST);
        }
        publication = requirePublication(candidate.getPublicationId());
        LocalDateTime now = LocalDateTime.now();
        if (publicationMapper.rejectVersion(versionId, actor.id(), now, comment) != 1
            || publicationMapper.updateStatus(publication.getId(), "rejected", now) != 1) {
            throw conflict("技能发布申请状态发生并发变化");
        }
        candidate.setStatus("rejected");
        candidate.setReviewedBy(actor.id());
        candidate.setReviewedAt(now);
        candidate.setReviewComment(comment);
        publication.setStatus("rejected");
        publication.setUpdatedAt(now);
        notifyOwner(
            publication, candidate, "rejected", "warning", "技能发布申请未通过",
            "技能「" + candidate.getNameSnapshot() + "」未通过审核：" + comment
        );
        auditService.recordCurrent(
            "skill.publication.reject", "skill_publication", versionId,
            "allow", "review_rejected", "comment_length=" + comment.length()
        );
        return view(publication, candidate, true);
    }

    /**
     * 处理{@code materialize}并返回对应结果。
     *
     * @param publication {@code publication}参数
     * @param snapshot 快照参数
     * @param files {@code files}参数
     * @param actorId 资源标识
     * @param now {@code now}参数
     * @return 处理结果
     */
    private MaterializedSkill materialize(
        AgentSkillPublication publication,
        AgentSkillPublicationVersion snapshot,
        List<AgentSkillPublicationFile> files,
        Long actorId,
        LocalDateTime now
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        Long systemSkillId = publication.getSystemSkillId();
        AgentSkill systemSkill;
        if (systemSkillId == null) {
            systemSkillId = idGenerator.nextId();
            systemSkill = new AgentSkill();
            systemSkill.setId(systemSkillId);
            systemSkill.setSkillKey(publicSkillKey(snapshot.getSourceSkillKeySnapshot(), publication.getId()));
            systemSkill.setName(snapshot.getNameSnapshot());
            systemSkill.setDescription(snapshot.getDescriptionSnapshot());
            systemSkill.setScopeType("system");
            systemSkill.setScopeId(null);
            systemSkill.setOwnerId(publication.getSourceOwnerId());
            systemSkill.setStatus("active");
            systemSkill.setRevisionNo(1L);
            systemSkill.setCreateBy(actorId);
            systemSkill.setCreateTime(now);
            systemSkill.setDelFlag("0");
            systemSkill.setExtraJson("{}");
            try {
                catalogMapper.insertSkill(systemSkill);
            } catch (DuplicateKeyException exception) {
                throw conflict("发布生成的系统技能标识冲突");
            }
        } else {
            systemSkill = catalogMapper.selectSkill(systemSkillId);
            if (systemSkill == null || !"system".equals(systemSkill.getScopeType())) {
                throw conflict("已发布系统技能不存在或作用域异常");
            }
            if (catalogMapper.updatePublishedSystemSkill(
                    systemSkillId, snapshot.getNameSnapshot(), snapshot.getDescriptionSnapshot(), actorId, now
                ) != 1) {
                throw conflict("系统技能元数据更新失败");
            }
        }

        Long systemVersionId = idGenerator.nextId();
        AgentSkillVersion version = new AgentSkillVersion();
        version.setId(systemVersionId);
        version.setSkillId(systemSkillId);
        version.setVersionNo(catalogMapper.selectNextVersionNo(systemSkillId));
        version.setContent(snapshot.getContentSnapshot());
        version.setContentHash(snapshot.getContentHash());
        version.setFileBundleHash(snapshot.getFileBundleHash());
        version.setManifestJson(snapshot.getManifestJson());
        version.setRuntimeRequirementsJson(snapshot.getRuntimeRequirementsJson());
        version.setStatus("published");
        version.setPublishedAt(now);
        version.setCreatedBy(actorId);
        version.setCreatedAt(now);
        catalogMapper.archivePreviouslyPublished(systemSkillId, systemVersionId);
        catalogMapper.insertVersion(version);

        for (AgentSkillPublicationFile snapshotFile : files) {
            AgentSkillFile file = new AgentSkillFile();
            file.setId(idGenerator.nextId());
            file.setSkillId(systemSkillId);
            file.setVersionId(systemVersionId);
            file.setPath(snapshotFile.getPath());
            file.setFileKind(snapshotFile.getFileKind());
            file.setContent(snapshotFile.getContent());
            file.setContentBytes(snapshotFile.getContentBytes() == null
                ? null : snapshotFile.getContentBytes().clone());
            file.setContentEncoding(snapshotFile.getContentEncoding());
            file.setContentHash(snapshotFile.getContentHash());
            file.setSizeBytes(snapshotFile.getSizeBytes());
            file.setCreateBy(actorId);
            file.setCreateTime(now);
            file.setDelFlag("0");
            fileMapper.upsert(file);
        }
        return new MaterializedSkill(systemSkillId, systemVersionId);
    }

    /**
     * 校验数据源快照，并在条件不满足时终止处理。
     *
     * @param version 版本参数
     * @param files {@code files}参数
     * @return 处理结果
     */
    private SnapshotStats validateSourceSnapshot(
        AgentSkillVersion version,
        List<AgentSkillFile> files
    ) {
        if (!metadataHash(
                version.getContent(), version.getManifestJson(), version.getRuntimeRequirementsJson()
            ).equals(version.getContentHash())) {
            throw conflict("技能版本内容哈希不一致，拒绝提交");
        }
        SnapshotStats stats = validateFiles(files.stream().map(this::sourceFile).toList());
        if (version.getFileBundleHash() != null
            && !version.getFileBundleHash().equals(stats.bundleHash())) {
            throw conflict("技能文件包哈希不一致，拒绝提交");
        }
        AgentSkillFile markdown = files.stream()
            .filter(file -> "SKILL.md".equals(file.getPath()))
            .findFirst().orElse(null);
        if (markdown == null || markdown.getContent() == null || markdown.getContent().isBlank()
            || !markdown.getContent().equals(version.getContent())) {
            throw conflict("SKILL.md 与技能版本主内容不一致");
        }
        return stats;
    }

    /**
     * 校验快照，并在条件不满足时终止处理。
     *
     * @param version 版本参数
     * @param files {@code files}参数
     */
    private void validateSnapshot(
        AgentSkillPublicationVersion version,
        List<AgentSkillPublicationFile> files
    ) {
        if (!metadataHash(
                version.getContentSnapshot(), version.getManifestJson(), version.getRuntimeRequirementsJson()
            ).equals(version.getContentHash())) {
            throw conflict("发布快照元数据哈希不一致");
        }
        SnapshotStats stats = validateFiles(files);
        if (!stats.bundleHash().equals(version.getFileBundleHash())
            || stats.fileCount() != version.getFileCount()
            || stats.totalSize() != version.getTotalSizeBytes()) {
            throw conflict("发布快照文件统计或文件包哈希不一致");
        }
        AgentSkillPublicationFile markdown = files.stream()
            .filter(file -> "SKILL.md".equals(file.getPath()))
            .findFirst().orElse(null);
        if (markdown == null || markdown.getContent() == null || markdown.getContent().isBlank()
            || !markdown.getContent().equals(version.getContentSnapshot())) {
            throw conflict("发布快照缺少有效的 SKILL.md");
        }
    }

    /**
     * 校验{@code Files}，并在条件不满足时终止处理。
     *
     * @param files {@code files}参数
     * @return 处理结果
     */
    private SnapshotStats validateFiles(List<AgentSkillPublicationFile> files) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (files.isEmpty() || files.size() > MAX_FILES) {
            throw new ServiceException("技能快照必须包含 1-256 个文件条目", HttpStatus.BAD_REQUEST);
        }
        Map<String, String> kinds = new LinkedHashMap<>();
        int fileCount = 0;
        long totalSize = 0;
        for (AgentSkillPublicationFile file : files) {
            validatePath(file.getPath());
            if (kinds.put(file.getPath(), file.getFileKind()) != null) {
                throw conflict("技能快照包含重复路径：" + file.getPath());
            }
            if ("directory".equals(file.getFileKind())) {
                if (file.getContent() != null || file.getContentBytes() != null
                    || file.getSizeBytes() == null || file.getSizeBytes() != 0
                    || !ContentHashing.sha256("").equals(file.getContentHash())) {
                    throw conflict("技能快照目录记录无效：" + file.getPath());
                }
                continue;
            }
            if (!"file".equals(file.getFileKind()) || file.getSizeBytes() == null
                || file.getSizeBytes() < 0 || file.getSizeBytes() > 5 * 1024 * 1024) {
                throw conflict("技能快照文件记录无效：" + file.getPath());
            }
            byte[] bytes;
            if ("utf8".equals(file.getContentEncoding()) && file.getContent() != null
                && file.getContentBytes() == null) {
                bytes = file.getContent().getBytes(StandardCharsets.UTF_8);
            } else if ("binary".equals(file.getContentEncoding()) && file.getContent() == null
                && file.getContentBytes() != null) {
                bytes = file.getContentBytes();
            } else {
                throw conflict("技能快照文件编码无效：" + file.getPath());
            }
            if (bytes.length != file.getSizeBytes()
                || !ContentHashing.sha256(bytes).equals(file.getContentHash())) {
                throw conflict("技能快照文件哈希不一致：" + file.getPath());
            }
            fileCount++;
            totalSize += bytes.length;
            if (totalSize > MAX_TOTAL_BYTES) {
                throw new ServiceException("技能快照总大小超过 32MB", HttpStatus.BAD_REQUEST);
            }
        }
        for (Map.Entry<String, String> entry : kinds.entrySet()) {
            String parent = parentPath(entry.getKey());
            while (parent != null) {
                if (kinds.containsKey(parent) && !"directory".equals(kinds.get(parent))) {
                    throw conflict("技能快照路径父级不是目录：" + entry.getKey());
                }
                parent = parentPath(parent);
            }
        }
        if (fileCount == 0) {
            throw new ServiceException("技能快照不包含普通文件", HttpStatus.BAD_REQUEST);
        }
        String material = files.stream()
            .sorted(Comparator.comparing(AgentSkillPublicationFile::getPath))
            .map(file -> file.getPath() + "\n" + file.getFileKind() + "\n" + file.getContentHash())
            .collect(Collectors.joining("\n"));
        return new SnapshotStats(ContentHashing.sha256(material), fileCount, totalSize);
    }

    /**
     * 处理数据源文件并返回对应结果。
     *
     * @param source 数据源参数
     * @return 处理结果
     */
    private AgentSkillPublicationFile sourceFile(AgentSkillFile source) {
        AgentSkillPublicationFile file = new AgentSkillPublicationFile();
        file.setPath(source.getPath());
        file.setFileKind(source.getFileKind());
        file.setContent("directory".equals(source.getFileKind()) ? null : source.getContent());
        file.setContentBytes(source.getContentBytes() == null ? null : source.getContentBytes().clone());
        file.setContentEncoding(source.getContentEncoding());
        file.setContentHash(source.getContentHash());
        file.setSizeBytes(source.getSizeBytes());
        return file;
    }

    /**
     * 处理快照文件并返回对应结果。
     *
     * @param versionId 资源标识
     * @param source 数据源参数
     * @return 处理结果
     */
    private AgentSkillPublicationFile snapshotFile(Long versionId, AgentSkillFile source) {
        AgentSkillPublicationFile file = sourceFile(source);
        file.setId(idGenerator.nextId());
        file.setPublicationVersionId(versionId);
        return file;
    }

    /**
     * 处理{@code view}并返回对应结果。
     *
     * @param publication {@code publication}参数
     * @param version 版本参数
     * @param includeFiles {@code includeFiles}参数
     * @return 处理结果
     */
    private SkillPublicationView view(
        AgentSkillPublication publication,
        AgentSkillPublicationVersion version,
        boolean includeFiles
    ) {
        if (version == null) {
            AgentSkill source = catalogMapper.selectSkill(publication.getSourceSkillId());
            return source == null ? new SkillPublicationView(
                publication.getId(), null, publication.getSourceSkillId(), publication.getSystemSkillId(),
                null, null, upper(publication.getStatus()), null, null,
                publication.getCurrentPublicVersionNo(), null, null, null, null, null,
                null, null, null, null, null, null, null, null, List.of()
            ) : unpublished(source);
        }
        List<AgentSkillPublicationFile> files = includeFiles
            ? publicationMapper.selectFiles(version.getId()) : List.of();
        String markdown = includeFiles ? files.stream()
            .filter(file -> "SKILL.md".equals(file.getPath()))
            .map(AgentSkillPublicationFile::getContent)
            .findFirst().orElse(null) : null;
        return new SkillPublicationView(
            publication.getId(), version.getId(), publication.getSourceSkillId(),
            publication.getSystemSkillId(), version.getNameSnapshot(), version.getDescriptionSnapshot(),
            upper(publication.getStatus()), version.getVersionNo(), upper(version.getStatus()),
            publication.getCurrentPublicVersionNo(),
            "pending".equals(version.getStatus()) ? version.getVersionNo() : null,
            version.getReviewComment(), version.getContentHash(), version.getFileCount(),
            version.getTotalSizeBytes(), version.getSubmittedBy(), version.getSubmittedAt(),
            version.getReviewedBy(), version.getReviewedAt(), version.getReviewComment(),
            version.getWithdrawnBy(), version.getWithdrawnAt(), markdown,
            includeFiles ? fileTree(files) : List.of()
        );
    }

    /**
     * 处理{@code unpublished}并返回对应结果。
     *
     * @param source 数据源参数
     * @return 处理结果
     */
    private SkillPublicationView unpublished(AgentSkill source) {
        return new SkillPublicationView(
            null, null, source.getId(), null, source.getName(), source.getDescription(),
            "UNPUBLISHED", null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, List.of()
        );
    }

    /**
     * 处理文件Tree并返回对应结果。
     *
     * @param files {@code files}参数
     * @return 符合条件的数据集合
     */
    private List<SkillPublicationFileNode> fileTree(List<AgentSkillPublicationFile> files) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        TreeNode root = new TreeNode("", "", true, 0);
        for (AgentSkillPublicationFile file : files.stream()
            .sorted(Comparator.comparing(AgentSkillPublicationFile::getPath)).toList()) {
            String[] parts = file.getPath().split("/");
            TreeNode parent = root;
            StringBuilder path = new StringBuilder();
            for (int index = 0; index < parts.length; index++) {
                String part = parts[index];
                if (!path.isEmpty()) path.append('/');
                path.append(part);
                boolean last = index == parts.length - 1;
                boolean directory = !last || "directory".equals(file.getFileKind());
                int size = last && file.getSizeBytes() != null ? file.getSizeBytes() : 0;
                String currentPath = path.toString();
                TreeNode child = parent.children.computeIfAbsent(part, ignored ->
                    new TreeNode(part, currentPath, directory, size));
                if (last) {
                    child.directory = directory;
                    child.size = size;
                }
                parent = child;
            }
        }
        return root.children.values().stream()
            .sorted(TreeNode.ORDER)
            .map(TreeNode::view)
            .toList();
    }

    /**
     * 校验{@code Reviewer}，并在条件不满足时终止处理。
     *
     * @param actor {@code actor}参数
     * @param publication {@code publication}参数
     * @param version 版本参数
     * @param action {@code action}参数
     */
    private void requireReviewer(
        CurrentPrincipal actor,
        AgentSkillPublication publication,
        AgentSkillPublicationVersion version,
        String action
    ) {
        require(actor, publication.getSourceSkillId(), version.getSourceSkillKeySnapshot(), action, Set.of());
    }

    /**
     * 判断{@code Review}是否满足要求。
     *
     * @param actor {@code actor}参数
     * @param version 版本参数
     * @param action {@code action}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean canReview(
        CurrentPrincipal actor,
        AgentSkillPublicationVersion version,
        String action
    ) {
        AgentSkillPublication publication = publicationMapper.selectPublication(version.getPublicationId());
        if (publication == null) {
            return false;
        }
        return canReview(actor, publication, version, action);
    }

    /**
     * 判断{@code Review}是否满足要求。
     *
     * @param actor {@code actor}参数
     * @param publication {@code publication}参数
     * @param version 版本参数
     * @param action {@code action}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean canReview(
        CurrentPrincipal actor,
        AgentSkillPublication publication,
        AgentSkillPublicationVersion version,
        String action
    ) {
        return authorizationEnforcer.decide(actor, permission(
            publication.getSourceSkillId(), version.getSourceSkillKeySnapshot(), action, Set.of()
        )).allowed();
    }

    /**
     * 校验{@code require}，并在条件不满足时终止处理。
     *
     * @param actor {@code actor}参数
     * @param resourceId 资源标识
     * @param resourceKey 资源Key参数
     * @param action {@code action}参数
     * @param relations {@code relations}参数
     */
    private void require(
        CurrentPrincipal actor,
        Long resourceId,
        String resourceKey,
        String action,
        Set<BusinessRelation> relations
    ) {
        authorizationEnforcer.requireAllowed(
            actor, permission(resourceId, resourceKey, action, relations)
        );
    }

    /**
     * 处理权限并返回对应结果。
     *
     * @param resourceId 资源标识
     * @param resourceKey 资源Key参数
     * @param action {@code action}参数
     * @param relations {@code relations}参数
     * @return 处理结果
     */
    private PermissionContext permission(
        Long resourceId,
        String resourceKey,
        String action,
        Set<BusinessRelation> relations
    ) {
        return new PermissionContext(
            "skill", resourceId, resourceKey, action, ResourceState.ACTIVE, true, relations, null
        );
    }

    /**
     * 校验OwnedPersonal技能，并在条件不满足时终止处理。
     *
     * @param skillId 资源标识
     * @param actor {@code actor}参数
     * @return 处理结果
     */
    private AgentSkill requireOwnedPersonalSkill(Long skillId, CurrentPrincipal actor) {
        AgentSkill skill = catalogMapper.selectSkill(skillId);
        if (skill == null) {
            throw notFound("个人技能不存在");
        }
        if (!"user".equals(skill.getScopeType()) || !actor.id().equals(skill.getOwnerId())) {
            throw new ServiceException("只能操作当前用户的个人技能", HttpStatus.FORBIDDEN);
        }
        return skill;
    }

    /**
     * 校验{@code Publication}，并在条件不满足时终止处理。
     *
     * @param publicationId 资源标识
     * @return 处理结果
     */
    private AgentSkillPublication requirePublication(Long publicationId) {
        AgentSkillPublication publication = publicationMapper.selectPublication(publicationId);
        if (publication == null) {
            throw notFound("技能发布记录不存在");
        }
        return publication;
    }

    /**
     * 校验版本，并在条件不满足时终止处理。
     *
     * @param versionId 资源标识
     * @return 处理结果
     */
    private AgentSkillPublicationVersion requireVersion(Long versionId) {
        AgentSkillPublicationVersion version = publicationMapper.selectVersion(versionId);
        if (version == null) {
            throw notFound("技能发布申请不存在");
        }
        return version;
    }

    /**
     * 校验{@code Human}，并在条件不满足时终止处理。
     *
     * @return 处理结果
     */
    private CurrentPrincipal requireHuman() {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        if (!principal.isHuman()) {
            throw new ServiceException("服务账号不能操作技能发布审核", HttpStatus.FORBIDDEN);
        }
        return principal;
    }

    /**
     * 处理{@code notifyOwner}相关逻辑。
     *
     * @param publication {@code publication}参数
     * @param version 版本参数
     * @param event 事件参数
     * @param level {@code level}参数
     * @param title {@code title}参数
     * @param content 待处理内容
     */
    private void notifyOwner(
        AgentSkillPublication publication,
        AgentSkillPublicationVersion version,
        String event,
        String level,
        String title,
        String content
    ) {
        String boundedContent = content.length() <= 2000 ? content : content.substring(0, 2000);
        notificationService.publish(
            new NotificationRecipient(publication.getSourceOwnerId(), group.aitools.nhs.platform.iam.domain.PrincipalType.HUMAN),
            new NotificationMessage(
                "skill-publication:" + event + ":" + version.getId(),
                "approval", level, title, boundedContent, "skill_publication", version.getId()
            )
        );
    }

    /**
     * 处理元数据Hash并返回对应结果。
     *
     * @param content 待处理内容
     * @param manifest {@code manifest}参数
     * @param runtime 运行时参数
     * @return 处理结果
     */
    private String metadataHash(String content, String manifest, String runtime) {
        return ContentHashing.sha256(content + "\n" + manifest + "\n" + runtime);
    }

    /**
     * 处理public技能Key并返回对应结果。
     *
     * @param sourceKey 数据源Key参数
     * @param publicationId 资源标识
     * @return 处理结果
     */
    private String publicSkillKey(String sourceKey, Long publicationId) {
        String suffix = "-" + publicationId;
        int sourceLength = Math.max(1, 128 - "public-".length() - suffix.length());
        String normalized = sourceKey.toLowerCase(Locale.ROOT);
        if (normalized.length() > sourceLength) normalized = normalized.substring(0, sourceLength);
        return "public-" + normalized + suffix;
    }

    /**
     * 校验{@code Path}，并在条件不满足时终止处理。
     *
     * @param path {@code path}参数
     */
    private void validatePath(String path) {
        if (path == null || path.isBlank() || path.length() > 512 || path.startsWith("/")
            || path.startsWith(".") || path.contains("\\") || path.contains("../")
            || path.endsWith("/..") || path.contains(":") || path.indexOf('\0') >= 0
            || path.chars().anyMatch(character -> character < 32)) {
            throw conflict("技能快照文件路径无效");
        }
    }

    /**
     * 处理{@code parentPath}并返回对应结果。
     *
     * @param path {@code path}参数
     * @return 处理结果
     */
    private String parentPath(String path) {
        int separator = path.lastIndexOf('/');
        return separator < 0 ? null : path.substring(0, separator);
    }

    /**
     * 处理{@code upper}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    /**
     * 处理{@code notFound}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException notFound(String message) {
        return new ServiceException(message, HttpStatus.NOT_FOUND);
    }

    /**
     * 处理{@code conflict}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException conflict(String message) {
        return new ServiceException(message, HttpStatus.CONFLICT);
    }

    /**
     * 封装快照Stats相关的不可变数据。
     */
    private record SnapshotStats(String bundleHash, int fileCount, long totalSize) {
    }

    /**
     * 封装Materialized技能相关的不可变数据。
     */
    private record MaterializedSkill(Long skillId, Long versionId) {
    }

    /**
     * 表示{@code TreeNode}相关的领域对象。
     */
    private static final class TreeNode {
        private static final Comparator<TreeNode> ORDER = Comparator
            .comparing((TreeNode node) -> !node.directory)
            .thenComparing(node -> node.name);

        private final String name;
        private final String path;
        private boolean directory;
        private int size;
        private final Map<String, TreeNode> children = new TreeMap<>();

        /**
         * 创建 {@code TreeNode} 实例并初始化所需依赖。
         *
         * @param name 名称
         * @param path {@code path}参数
         * @param directory 目录参数
         * @param size 数量上限
         */
        private TreeNode(String name, String path, boolean directory, int size) {
            this.name = name;
            this.path = path;
            this.directory = directory;
            this.size = size;
        }

        /**
         * 处理{@code view}并返回对应结果。
         *
         * @return 处理结果
         */
        private SkillPublicationFileNode view() {
            List<SkillPublicationFileNode> childViews = directory
                ? children.values().stream().sorted(ORDER).map(TreeNode::view).toList()
                : List.of();
            return new SkillPublicationFileNode(name, path, directory, size, childViews);
        }
    }
}
