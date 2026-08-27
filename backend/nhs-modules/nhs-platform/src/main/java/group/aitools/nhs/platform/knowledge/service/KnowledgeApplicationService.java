package group.aitools.nhs.platform.knowledge.service;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.AuthorizationDecision;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionEffect;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.knowledge.domain.AgentKnowledgeBase;
import group.aitools.nhs.platform.knowledge.domain.AgentKnowledgeDirectory;
import group.aitools.nhs.platform.knowledge.domain.AgentKnowledgeDocument;
import group.aitools.nhs.platform.knowledge.mapper.KnowledgeCatalogMapper;
import group.aitools.nhs.platform.knowledge.web.CreateKnowledgeBaseRequest;
import group.aitools.nhs.platform.knowledge.web.CreateKnowledgeDirectoryRequest;
import group.aitools.nhs.platform.knowledge.web.KnowledgeBaseView;
import group.aitools.nhs.platform.knowledge.web.KnowledgeChunkView;
import group.aitools.nhs.platform.knowledge.web.KnowledgeDirectoryView;
import group.aitools.nhs.platform.knowledge.web.KnowledgeDocumentView;
import group.aitools.nhs.platform.knowledge.web.KnowledgeParseJobView;
import group.aitools.nhs.platform.knowledge.web.KnowledgeTreeView;
import group.aitools.nhs.platform.knowledge.web.UpdateKnowledgeBaseRequest;
import group.aitools.nhs.platform.knowledge.web.UpdateKnowledgeDirectoryRequest;
import group.aitools.nhs.platform.knowledge.web.UpdateKnowledgeDocumentRequest;
import group.aitools.nhs.platform.model.domain.AgentModel;
import group.aitools.nhs.platform.model.mapper.AgentModelMapper;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 负责知识库相关的业务编排与领域规则处理。
 */
@Service
public class KnowledgeApplicationService {

    private static final long MAX_UPLOAD_BYTES = 20L * 1024 * 1024;
    private static final int MAX_CHUNK_OFFSET = 100_000;
    private static final int MAX_DOCUMENT_TAGS = 32;
    private static final int MAX_DOCUMENT_TAG_LENGTH = 64;
    private static final Pattern KNOWLEDGE_KEY = Pattern.compile("[a-z][a-z0-9._-]{0,127}");

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final KnowledgeAuthorizationContextFactory contextFactory;
    private final PlatformIdGenerator idGenerator;
    private final KnowledgeCatalogMapper mapper;
    private final KnowledgeFileStorage storage;
    private final AgentModelMapper modelMapper;
    private final JsonMapper jsonMapper;
    private final KnowledgeOperationAuditService operationAudit;
    private KnowledgeDirectoryAccessService directoryAccess;

    /**
     * 创建 {@code KnowledgeApplicationService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param authorizationEnforcer 授权Enforcer参数
     * @param contextFactory 待处理内容
     * @param idGenerator {@code idGenerator}参数
     * @param mapper {@code mapper}参数
     * @param storage 存储参数
     * @param modelMapper 模型Mapper参数
     * @param jsonMapper {@code jsonMapper}参数
     * @param operationAudit 操作审计参数
     */
    public KnowledgeApplicationService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        KnowledgeAuthorizationContextFactory contextFactory,
        PlatformIdGenerator idGenerator,
        KnowledgeCatalogMapper mapper,
        KnowledgeFileStorage storage,
        AgentModelMapper modelMapper,
        JsonMapper jsonMapper,
        KnowledgeOperationAuditService operationAudit
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.contextFactory = contextFactory;
        this.idGenerator = idGenerator;
        this.mapper = mapper;
        this.storage = storage;
        this.modelMapper = modelMapper;
        this.jsonMapper = jsonMapper;
        this.operationAudit = operationAudit;
    }

    /**
     * 设置目录Access。
     *
     * @param directoryAccess 目录Access参数
     */
    @Autowired(required = false)
    public void setDirectoryAccess(KnowledgeDirectoryAccessService directoryAccess) {
        this.directoryAccess = directoryAccess;
    }

    /**
     * 查询{@code list}列表。
     *
     * @param search {@code search}参数
     * @param includeInactive {@code includeInactive}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<KnowledgeBaseView> list(String search, boolean includeInactive, int limit) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        return mapper.selectBases(normalizeSearch(search), includeInactive, limit).stream()
            .filter(base -> visible(principal, base, "list"))
            .map(base -> KnowledgeBaseView.from(base, jsonMapper))
            .toList();
    }

    /**
     * 获取{@code get}。
     *
     * @param baseId 资源标识
     * @return 处理结果
     */
    public KnowledgeBaseView get(Long baseId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentKnowledgeBase base = requireBase(baseId);
        authorizationEnforcer.requireAllowed(
            principal, contextFactory.context(principal, base, "view", true)
        );
        return KnowledgeBaseView.from(base, jsonMapper);
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBaseView create(CreateKnowledgeBaseRequest request) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, contextFactory.createContext());
        String key = request.knowledgeKey().strip().toLowerCase(java.util.Locale.ROOT);
        if (!KNOWLEDGE_KEY.matcher(key).matches()) {
            throw badRequest("知识库标识无效");
        }
        KnowledgeBaseConfig config = KnowledgeBaseConfig.from(request.config());
        validateEmbeddingModel(config);
        AgentKnowledgeBase base = new AgentKnowledgeBase();
        base.setId(idGenerator.nextId());
        base.setKnowledgeKey(key);
        base.setName(requiredText(request.name(), 255, "知识库名称"));
        base.setDescription(optionalText(request.description(), 12000));
        base.setProviderType("postgres_pgvector");
        base.setVisibility(requiredEnum(
            request.visibility(), Set.of("private", "enterprise_shared", "restricted"), "可见性"
        ));
        base.setStatus("active");
        base.setConfigJson(jsonMapper.writeValueAsString(config.toMap()));
        base.setOwnerId(principal.id());
        base.setRevisionNo(1L);
        base.setCreateBy(principal.id());
        base.setCreateTime(LocalDateTime.now());
        base.setDelFlag("0");
        base.setExtraJson("{}");
        try {
            mapper.insertBase(base);
        } catch (DuplicateKeyException exception) {
            throw conflict("知识库标识已存在：" + key);
        }
        return KnowledgeBaseView.from(base, jsonMapper);
    }

    /**
     * 更新{@code update}。
     *
     * @param baseId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBaseView update(Long baseId, UpdateKnowledgeBaseRequest request) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        mapper.lockBase(baseId);
        AgentKnowledgeBase base = requireBase(baseId);
        authorizationEnforcer.requireAllowed(
            principal, contextFactory.context(principal, base, "update", true)
        );
        if (!request.expectedRevision().equals(base.getRevisionNo())) {
            throw conflict("知识库已被其他请求修改");
        }
        KnowledgeBaseConfig config = KnowledgeBaseConfig.from(request.config());
        validateEmbeddingModel(config);
        base.setName(requiredText(request.name(), 255, "知识库名称"));
        base.setDescription(optionalText(request.description(), 12000));
        base.setVisibility(requiredEnum(
            request.visibility(), Set.of("private", "enterprise_shared", "restricted"), "可见性"
        ));
        base.setStatus(requiredEnum(request.status(), Set.of("active", "disabled"), "状态"));
        base.setConfigJson(jsonMapper.writeValueAsString(config.toMap()));
        base.setUpdateBy(principal.id());
        base.setUpdateTime(LocalDateTime.now());
        if (mapper.updateBase(base) != 1) {
            throw conflict("知识库已被其他请求修改");
        }
        base.setRevisionNo(base.getRevisionNo() + 1);
        return KnowledgeBaseView.from(base, jsonMapper);
    }

    /**
     * 删除{@code delete}。
     *
     * @param baseId 资源标识
     * @param expectedRevision {@code expectedRevision}参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long baseId, Long expectedRevision) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        mapper.lockBase(baseId);
        AgentKnowledgeBase base = requireBase(baseId);
        authorizationEnforcer.requireAllowed(
            principal, contextFactory.context(principal, base, "delete", true)
        );
        if (!expectedRevision.equals(base.getRevisionNo())) {
            throw conflict("知识库已被其他请求修改");
        }
        if (mapper.countActiveReferences(baseId) > 0) {
            throw conflict("知识库仍被草稿或已发布 Agent 引用，不能删除");
        }
        if (mapper.softDeleteBase(baseId, expectedRevision, principal.id(), LocalDateTime.now()) != 1) {
            throw conflict("知识库已被其他请求修改");
        }
    }

    /**
     * 处理{@code directories}并返回对应结果。
     *
     * @param baseId 资源标识
     * @return 符合条件的数据集合
     */
    public List<KnowledgeDirectoryView> directories(Long baseId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentKnowledgeBase base = requireBase(baseId);
        authorizationEnforcer.requireAllowed(
            principal, contextFactory.context(principal, base, "read", true)
        );
        KnowledgeDirectoryAccessService.DirectoryAccess access = directoryAccess(
            principal, baseId, "read"
        );
        return mapper.selectDirectories(baseId).stream()
            .filter(directory -> access.allDirectories() || access.allows(directory.getId()))
            .map(KnowledgeDirectoryView::from)
            .toList();
    }

    /**
 * 处理{@code tree}并返回对应结果。
 * Returns the complete local catalog as flat rows that preserve parent relationships. */
    public KnowledgeTreeView tree(Long baseId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentKnowledgeBase base = requireBase(baseId);
        authorizationEnforcer.requireAllowed(
            principal, contextFactory.context(principal, base, "read", true)
        );
        KnowledgeDirectoryAccessService.DirectoryAccess access = directoryAccess(
            principal, baseId, "read"
        );
        List<KnowledgeDirectoryView> directories = mapper.selectDirectories(baseId).stream()
            .filter(directory -> access.allDirectories() || access.allows(directory.getId()))
            .map(KnowledgeDirectoryView::from)
            .toList();
        List<KnowledgeDocumentView> documents = mapper.selectDocuments(baseId, 500).stream()
            .filter(document -> access.allDirectories() || access.allows(document.getDirectoryId()))
            .map(document -> KnowledgeDocumentView.from(document, jsonMapper))
            .toList();
        return new KnowledgeTreeView(directories, documents);
    }

    /**
     * 创建并保存目录。
     *
     * @param baseId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeDirectoryView createDirectory(Long baseId, CreateKnowledgeDirectoryRequest request) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        mapper.lockBase(baseId);
        AgentKnowledgeBase base = requireActiveLocalBase(baseId);
        authorizationEnforcer.requireAllowed(
            principal, contextFactory.context(principal, base, "update", true)
        );
        if (request == null) {
            throw badRequest("知识目录创建内容不能为空");
        }
        String name = directoryName(request.name());
        Long parentId = request.parentId();
        if (parentId != null) {
            requireDirectory(baseId, parentId);
        }
        requireDirectoryAccess(principal, baseId, parentId, "write");
        if (mapper.selectDirectoryNameConflict(baseId, parentId, name, null) != null) {
            throw conflict("同级知识目录名称已存在");
        }
        LocalDateTime now = LocalDateTime.now();
        AgentKnowledgeDirectory directory = new AgentKnowledgeDirectory();
        directory.setId(idGenerator.nextId());
        directory.setKnowledgeBaseId(baseId);
        directory.setParentId(parentId);
        directory.setDirectoryKey("dir-" + directory.getId());
        directory.setName(name);
        directory.setRevisionNo(1L);
        directory.setCreatedBy(principal.id());
        directory.setCreatedAt(now);
        try {
            mapper.insertDirectory(directory);
        } catch (DuplicateKeyException exception) {
            throw conflict("同级知识目录名称已存在");
        }
        operationAudit.record(
            principal, "knowledge_directory_create", "knowledge_directory", directory.getId(),
            "baseId=" + baseId + ";parentId=" + parentId + ";revision=1"
        );
        return KnowledgeDirectoryView.from(requireDirectory(baseId, directory.getId()));
    }

    /**
     * 更新目录。
     *
     * @param baseId 资源标识
     * @param directoryId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeDirectoryView updateDirectory(
        Long baseId,
        Long directoryId,
        UpdateKnowledgeDirectoryRequest request
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        mapper.lockBase(baseId);
        AgentKnowledgeBase base = requireActiveLocalBase(baseId);
        authorizationEnforcer.requireAllowed(
            principal, contextFactory.context(principal, base, "update", true)
        );
        mapper.lockDirectory(directoryId);
        AgentKnowledgeDirectory current = requireDirectory(baseId, directoryId);
        requireDirectoryAccess(principal, baseId, directoryId, "write");
        if (request == null || request.expectedRevision() == null
            || !request.expectedRevision().equals(current.getRevisionNo())) {
            throw conflict("知识目录已被其他请求修改");
        }
        if (!request.namePresent() && !request.parentPresent()) {
            throw badRequest("知识目录更新至少需要名称或父目录");
        }
        String name = request.namePresent() ? directoryName(request.name()) : current.getName();
        Long parentId = request.parentPresent() ? request.parentId() : current.getParentId();
        if (Objects.equals(parentId, directoryId)) {
            throw conflict("知识目录不能移动到自身");
        }
        if (parentId != null) {
            requireDirectory(baseId, parentId);
            requireDirectoryAccess(principal, baseId, parentId, "write");
            if (mapper.candidateParentContainsDirectory(baseId, parentId, directoryId)) {
                throw conflict("知识目录不能移动到自身的子目录");
            }
        }
        if (mapper.selectDirectoryNameConflict(baseId, parentId, name, directoryId) != null) {
            throw conflict("同级知识目录名称已存在");
        }
        boolean changed = !Objects.equals(name, current.getName())
            || !Objects.equals(parentId, current.getParentId());
        if (!changed) {
            return KnowledgeDirectoryView.from(current);
        }
        current.setName(name);
        current.setParentId(parentId);
        current.setUpdatedBy(principal.id());
        current.setUpdatedAt(LocalDateTime.now());
        try {
            if (mapper.updateDirectory(current) != 1) {
                throw conflict("知识目录已被其他请求修改");
            }
        } catch (DuplicateKeyException exception) {
            throw conflict("同级知识目录名称已存在");
        }
        operationAudit.record(
            principal, "knowledge_directory_update", "knowledge_directory", directoryId,
            "baseId=" + baseId + ";parentId=" + parentId + ";revision=" + request.expectedRevision()
        );
        return KnowledgeDirectoryView.from(requireDirectory(baseId, directoryId));
    }

    /**
     * 删除目录。
     *
     * @param baseId 资源标识
     * @param directoryId 资源标识
     * @param expectedRevision {@code expectedRevision}参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteDirectory(Long baseId, Long directoryId, Long expectedRevision) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        mapper.lockBase(baseId);
        AgentKnowledgeBase base = requireActiveLocalBase(baseId);
        authorizationEnforcer.requireAllowed(
            principal, contextFactory.context(principal, base, "delete", true)
        );
        mapper.lockDirectory(directoryId);
        AgentKnowledgeDirectory directory = requireDirectory(baseId, directoryId);
        requireDirectoryAccess(principal, baseId, directoryId, "write");
        if (expectedRevision == null || !expectedRevision.equals(directory.getRevisionNo())) {
            throw conflict("知识目录已被其他请求修改");
        }
        if (mapper.countDirectoryEntries(baseId, directoryId) > 0) {
            throw conflict("非空知识目录不能删除，请先移动其中的内容");
        }
        if (mapper.softDeleteDirectory(
            baseId, directoryId, expectedRevision, principal.id(), LocalDateTime.now()
        ) != 1) {
            throw conflict("知识目录已被其他请求修改");
        }
        operationAudit.record(
            principal, "knowledge_directory_delete", "knowledge_directory", directoryId,
            "baseId=" + baseId + ";revision=" + expectedRevision
        );
    }

    /**
     * 处理{@code documents}并返回对应结果。
     *
     * @param baseId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<KnowledgeDocumentView> documents(Long baseId, int limit) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentKnowledgeBase base = requireBase(baseId);
        authorizationEnforcer.requireAllowed(
            principal, contextFactory.context(principal, base, "view", true)
        );
        KnowledgeDirectoryAccessService.DirectoryAccess access = directoryAccess(
            principal, baseId, "read"
        );
        return mapper.selectDocuments(baseId, limit).stream()
            .filter(document -> access.allDirectories() || access.allows(document.getDirectoryId()))
            .map(document -> KnowledgeDocumentView.from(document, jsonMapper))
            .toList();
    }

    /**
 * 处理{@code chunks}并返回对应结果。
 *
     * Lists parsed chunks for the document after checking the knowledge-base read boundary.
     * The persisted embedding vector is intentionally never mapped to the web view.
     */
    public List<KnowledgeChunkView> chunks(Long baseId, Long documentId, int offset, int limit) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentKnowledgeBase base = requireBase(baseId);
        authorizationEnforcer.requireAllowed(
            principal, contextFactory.context(principal, base, "read", true)
        );
        AgentKnowledgeDocument document = requireDocument(baseId, documentId);
        requireDirectoryAccess(principal, baseId, document.getDirectoryId(), "read");
        if (offset < 0 || offset > MAX_CHUNK_OFFSET || limit < 1 || limit > 200) {
            throw badRequest("切片分页参数无效");
        }
        return mapper.selectChunks(documentId, offset, limit).stream()
            .map(chunk -> KnowledgeChunkView.from(chunk, jsonMapper))
            .toList();
    }

    /**
 * 处理{@code download}并返回对应结果。
 *
     * Opens the original local document after object authorization. The controller owns
     * the returned stream and closes it when the HTTP response completes.
     */
    public DocumentDownload download(Long baseId, Long documentId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentKnowledgeBase base = requireBase(baseId);
        authorizationEnforcer.requireAllowed(
            principal, contextFactory.context(principal, base, "read", true)
        );
        AgentKnowledgeDocument document = requireDocument(baseId, documentId);
        requireDirectoryAccess(principal, baseId, document.getDirectoryId(), "read");
        if (!"local".equals(document.getStorageType())
            || document.getStorageRef() == null || document.getStorageRef().isBlank()) {
            throw conflict("该知识文档没有可下载的本地原档");
        }
        return new DocumentDownload(document, storage.open(document.getStorageRef()));
    }

    /**
     * 处理{@code upload}并返回对应结果。
     *
     * @param baseId 资源标识
     * @param file 文件参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeDocumentView upload(Long baseId, MultipartFile file) {
        return upload(baseId, file, null);
    }

    /**
     * 处理{@code upload}并返回对应结果。
     *
     * @param baseId 资源标识
     * @param file 文件参数
     * @param directoryId 资源标识
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeDocumentView upload(Long baseId, MultipartFile file, Long directoryId) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentKnowledgeBase base = requireActiveLocalBase(baseId);
        authorizationEnforcer.requireAllowed(
            principal, contextFactory.context(principal, base, "upload", true)
        );
        if (directoryId != null) {
            requireDirectory(baseId, directoryId);
        }
        requireDirectoryAccess(principal, baseId, directoryId, "write");
        if (file == null || file.isEmpty() || file.getSize() <= 0 || file.getSize() > MAX_UPLOAD_BYTES) {
            throw badRequest("文档为空或超过 20MB 限制");
        }
        String fileName = safeFileName(file.getOriginalFilename());
        Long documentId = idGenerator.nextId();
        KnowledgeFileStorage.StoredFile stored;
        try (InputStream input = file.getInputStream()) {
            stored = storage.put(documentId, input, file.getSize());
        } catch (IOException exception) {
            throw new ServiceException("无法读取上传文档", HttpStatus.BAD_REQUEST);
        }
        try {
            // Serialize directory deletion/moves against an upload that targets the same base.
            mapper.lockBase(baseId);
            if (directoryId != null) {
                mapper.lockDirectory(directoryId);
                requireDirectory(baseId, directoryId);
            }
            requireDirectoryAccess(principal, baseId, directoryId, "write");
            if (mapper.selectDuplicateDocument(baseId, stored.sha256()) != null) {
                throw conflict("知识库中已存在内容相同的文档");
            }
            LocalDateTime now = LocalDateTime.now();
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("originalName", fileName);
            metadata.put("tags", List.of());
            if (file.getContentType() != null && !file.getContentType().isBlank()) {
                metadata.put("declaredContentType", file.getContentType().strip());
            }
            AgentKnowledgeDocument document = new AgentKnowledgeDocument();
            document.setId(documentId);
            document.setKnowledgeBaseId(baseId);
            document.setDocumentKey("doc-" + documentId);
            document.setName(fileName);
            document.setContentHash(stored.sha256());
            document.setParserType("auto");
            document.setStatus("pending");
            document.setChunkCount(0);
            document.setMetadataJson(jsonMapper.writeValueAsString(metadata));
            document.setStorageType("local");
            document.setStorageRef(stored.storageRef());
            document.setMimeType(optionalText(file.getContentType(), 128));
            document.setSizeBytes(stored.sizeBytes());
            document.setDirectoryId(directoryId);
            document.setCatalogRevisionNo(1L);
            document.setTagsJson("[]");
            document.setRemark(null);
            document.setRevisionNo(1L);
            document.setCreatedBy(principal.id());
            document.setCreatedAt(now);
            document.setUpdatedAt(now);
            document.setDelFlag("0");
            try {
                mapper.insertDocument(document);
            } catch (DuplicateKeyException exception) {
                throw conflict("知识库中已存在内容相同的文档");
            }
            operationAudit.record(
                principal, "knowledge_document_create", "knowledge_document", documentId,
                "baseId=" + baseId + ";directoryId=" + directoryId + ";revision=1"
            );
            return KnowledgeDocumentView.from(document, jsonMapper);
        } catch (RuntimeException exception) {
            storage.delete(stored.storageRef());
            throw exception;
        }
    }

    /**
     * 更新文档。
     *
     * @param baseId 资源标识
     * @param documentId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeDocumentView updateDocument(
        Long baseId,
        Long documentId,
        UpdateKnowledgeDocumentRequest request
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        mapper.lockBase(baseId);
        AgentKnowledgeBase base = requireActiveLocalBase(baseId);
        authorizationEnforcer.requireAllowed(
            principal, contextFactory.context(principal, base, "update", true)
        );
        mapper.lockDocument(documentId);
        AgentKnowledgeDocument current = requireDocument(baseId, documentId);
        requireDirectoryAccess(principal, baseId, current.getDirectoryId(), "write");
        Long catalogRevision = current.getCatalogRevisionNo() == null
            ? 1L : current.getCatalogRevisionNo();
        if (request == null || request.expectedRevision() == null
            || !request.expectedRevision().equals(catalogRevision)) {
            throw conflict("知识文档已被其他请求修改");
        }
        if (!request.namePresent() && !request.directoryPresent()
            && !request.tagsPresent() && !request.remarkPresent()) {
            throw badRequest("知识文档更新至少需要名称、目录、标签或备注");
        }
        String name = request.namePresent()
            ? safeDocumentName(request.name()) : current.getName();
        Long directoryIdTarget = request.directoryPresent()
            ? request.directoryId() : current.getDirectoryId();
        if (directoryIdTarget != null) {
            requireDirectory(baseId, directoryIdTarget);
        }
        requireDirectoryAccess(principal, baseId, directoryIdTarget, "write");
        String tagsJson = current.getTagsJson();
        if (request.tagsPresent()) {
            tagsJson = jsonMapper.writeValueAsString(normalizeTags(request.tags()));
        }
        if (tagsJson == null || tagsJson.isBlank()) {
            tagsJson = "[]";
        }
        String remark = request.remarkPresent()
            ? normalizeRemark(request.remark()) : current.getRemark();
        boolean changed = !Objects.equals(name, current.getName())
            || !Objects.equals(directoryIdTarget, current.getDirectoryId())
            || !Objects.equals(tagsJson, current.getTagsJson())
            || !Objects.equals(remark, current.getRemark());
        if (!changed) {
            return KnowledgeDocumentView.from(current, jsonMapper);
        }
        current.setName(name);
        current.setDirectoryId(directoryIdTarget);
        current.setTagsJson(tagsJson);
        current.setRemark(remark);
        current.setCatalogRevisionNo(catalogRevision);
        current.setUpdatedAt(LocalDateTime.now());
        try {
            if (mapper.updateDocumentCatalog(current) != 1) {
                throw conflict("知识文档已被其他请求修改");
            }
        } catch (DuplicateKeyException exception) {
            throw conflict("知识文档目录位置已被占用");
        }
        operationAudit.record(
            principal, "knowledge_document_update", "knowledge_document", documentId,
            "baseId=" + baseId + ";directoryId=" + directoryIdTarget
                + ";revision=" + request.expectedRevision()
        );
        return KnowledgeDocumentView.from(requireDocument(baseId, documentId), jsonMapper);
    }

    /**
     * 处理{@code queueParse}并返回对应结果。
     *
     * @param baseId 资源标识
     * @param documentId 资源标识
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeParseJobView queueParse(Long baseId, Long documentId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentKnowledgeBase base = requireActiveLocalBase(baseId);
        authorizationEnforcer.requireAllowed(
            principal, contextFactory.context(principal, base, "parse", true)
        );
        mapper.lockDocument(documentId);
        AgentKnowledgeDocument document = requireDocument(baseId, documentId);
        requireDirectoryAccess(principal, baseId, document.getDirectoryId(), "write");
        if ("processing".equals(document.getStatus())) {
            throw conflict("文档正在解析中");
        }
        int increment = "pending".equals(document.getStatus()) ? 0 : 1;
        Long revision = document.getRevisionNo() + increment;
        LocalDateTime now = LocalDateTime.now();
        if (mapper.queueDocument(documentId, document.getRevisionNo(), increment, now) != 1) {
            throw conflict("文档状态已发生变化");
        }
        Long jobId = idGenerator.nextId();
        String bizKey = "knowledge-document:" + documentId + ":" + revision;
        String payload = jsonMapper.writeValueAsString(Map.of(
            "knowledgeBaseId", baseId, "documentId", documentId, "revision", revision
        ));
        try {
            mapper.insertParseJob(jobId, bizKey, payload, now);
        } catch (DuplicateKeyException exception) {
            throw conflict("该文档修订版已经提交解析");
        }
        return new KnowledgeParseJobView(jobId, documentId, revision, "queued");
    }

    /**
     * 删除文档。
     *
     * @param baseId 资源标识
     * @param documentId 资源标识
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteDocument(Long baseId, Long documentId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentKnowledgeBase base = requireBase(baseId);
        authorizationEnforcer.requireAllowed(
            principal, contextFactory.context(principal, base, "upload", true)
        );
        mapper.lockDocument(documentId);
        AgentKnowledgeDocument document = requireDocument(baseId, documentId);
        requireDirectoryAccess(principal, baseId, document.getDirectoryId(), "write");
        if (mapper.softDeleteDocument(baseId, documentId, LocalDateTime.now()) != 1) {
            throw conflict("解析中的文档不能删除");
        }
        operationAudit.record(
            principal, "knowledge_document_delete", "knowledge_document", documentId,
            "baseId=" + baseId
        );
    }

    /**
     * 校验Embedding模型，并在条件不满足时终止处理。
     *
     * @param config {@code config}参数
     */
    private void validateEmbeddingModel(KnowledgeBaseConfig config) {
        if (config.embeddingModelId() == null) {
            return;
        }
        AgentModel model = modelMapper.selectModelById(config.embeddingModelId());
        if (model == null || !"embedding".equals(model.getModelType())
            || !"active".equals(model.getStatus())) {
            throw badRequest("知识库向量模型不存在、类型错误或未启用");
        }
    }

    /**
     * 处理{@code visible}并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param base {@code base}参数
     * @param action {@code action}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean visible(CurrentPrincipal principal, AgentKnowledgeBase base, String action) {
        AuthorizationDecision decision = authorizationEnforcer.decide(
            principal, contextFactory.context(principal, base, action, true)
        );
        return decision.effect() == PermissionEffect.ALLOW
            || decision.effect() == PermissionEffect.APPROVAL_REQUIRED;
    }

    /**
     * 校验{@code ActiveLocalBase}，并在条件不满足时终止处理。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    private AgentKnowledgeBase requireActiveLocalBase(Long id) {
        AgentKnowledgeBase base = requireBase(id);
        if (!"active".equals(base.getStatus()) || !"postgres_pgvector".equals(base.getProviderType())) {
            throw conflict("知识库未启用或不支持本地文档操作");
        }
        return base;
    }

    /**
     * 校验{@code Base}，并在条件不满足时终止处理。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    private AgentKnowledgeBase requireBase(Long id) {
        AgentKnowledgeBase base = mapper.selectBaseById(id);
        if (base == null) {
            throw new ServiceException("知识库不存在", HttpStatus.NOT_FOUND);
        }
        return base;
    }

    /**
     * 校验目录，并在条件不满足时终止处理。
     *
     * @param baseId 资源标识
     * @param directoryId 资源标识
     * @return 处理结果
     */
    private AgentKnowledgeDirectory requireDirectory(Long baseId, Long directoryId) {
        AgentKnowledgeDirectory directory = mapper.selectDirectoryById(directoryId);
        if (directory == null || !baseId.equals(directory.getKnowledgeBaseId())) {
            throw new ServiceException("知识目录不存在", HttpStatus.NOT_FOUND);
        }
        return directory;
    }

    /**
     * 校验文档，并在条件不满足时终止处理。
     *
     * @param baseId 资源标识
     * @param documentId 资源标识
     * @return 处理结果
     */
    private AgentKnowledgeDocument requireDocument(Long baseId, Long documentId) {
        AgentKnowledgeDocument document = mapper.selectDocumentById(documentId);
        if (document == null || !baseId.equals(document.getKnowledgeBaseId())) {
            throw new ServiceException("知识文档不存在", HttpStatus.NOT_FOUND);
        }
        return document;
    }

    /**
     * 处理目录Access并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param baseId 资源标识
     * @param permission 权限参数
     * @return 处理结果
     */
    private KnowledgeDirectoryAccessService.DirectoryAccess directoryAccess(
        CurrentPrincipal principal, Long baseId, String permission
    ) {
        return directoryAccess == null
            ? KnowledgeDirectoryAccessService.DirectoryAccess.all()
            : directoryAccess.access(principal, baseId, permission);
    }

    /**
     * 校验目录Access，并在条件不满足时终止处理。
     *
     * @param principal 当前操作主体
     * @param baseId 资源标识
     * @param directoryId 资源标识
     * @param permission 权限参数
     */
    private void requireDirectoryAccess(
        CurrentPrincipal principal, Long baseId, Long directoryId, String permission
    ) {
        if (directoryAccess != null) {
            directoryAccess.require(principal, baseId, directoryId, permission);
        }
    }

    /**
     * 处理safe文件Name并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String safeFileName(String value) {
        String name = value == null ? "document" : value.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        return safeDocumentName(name);
    }

    /**
     * 处理safe文档Name并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String safeDocumentName(String value) {
        if (value == null) {
            throw badRequest("文档名称无效");
        }
        String name = value.strip();
        if (name.isBlank() || name.length() > 255 || name.equals(".") || name.equals("..")
            || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
            || name.chars().anyMatch(Character::isISOControl)) {
            throw badRequest("文档名称无效");
        }
        return name;
    }

    /**
     * 处理目录Name并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String directoryName(String value) {
        if (value == null) {
            throw badRequest("知识目录名称无效");
        }
        String name = value.strip();
        if (name.isBlank() || name.length() > 255 || name.equals(".") || name.equals("..")
            || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
            || name.chars().anyMatch(Character::isISOControl)) {
            throw badRequest("知识目录名称无效");
        }
        return name;
    }

    /**
     * 处理{@code normalizeTags}并返回对应结果。
     *
     * @param values {@code values}参数
     * @return 符合条件的数据集合
     */
    private List<String> normalizeTags(List<String> values) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (values == null) {
            return List.of();
        }
        if (values.size() > MAX_DOCUMENT_TAGS) {
            throw badRequest("文档标签不能超过" + MAX_DOCUMENT_TAGS + "个");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Set<String> folded = new java.util.HashSet<>();
        for (String value : values) {
            if (value == null) {
                throw badRequest("文档标签无效");
            }
            String tag = value.strip();
            if (tag.isBlank() || tag.length() > MAX_DOCUMENT_TAG_LENGTH
                || tag.chars().anyMatch(Character::isISOControl)) {
                throw badRequest("文档标签无效或超过长度限制");
            }
            if (!folded.add(tag.toLowerCase(java.util.Locale.ROOT))) {
                throw badRequest("文档标签不能重复");
            }
            result.add(tag);
        }
        return List.copyOf(result);
    }

    /**
     * 处理{@code normalizeRemark}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalizeRemark(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String remark = value.strip();
        if (remark.length() > 4000 || remark.chars().anyMatch(
            ch -> ch == 0 || (Character.isISOControl(ch) && ch != '\n' && ch != '\r' && ch != '\t')
        )) {
            throw badRequest("文档备注无效或超过长度限制");
        }
        return remark;
    }

    /**
     * 处理{@code normalizeSearch}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalizeSearch(String value) {
        return value == null || value.isBlank() ? null : requiredText(value, 128, "搜索条件");
    }

    /**
     * 校验{@code dText}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param maxLength {@code maxLength}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String requiredText(String value, int maxLength, String label) {
        String text = optionalText(value, maxLength);
        if (text == null) {
            throw badRequest(label + "无效");
        }
        return text;
    }

    /**
     * 处理{@code optionalText}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param maxLength {@code maxLength}参数
     * @return 处理结果
     */
    private String optionalText(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.strip();
        if (text.length() > maxLength || text.indexOf('\0') >= 0 || text.indexOf('\r') >= 0) {
            throw badRequest("文本内容无效或超过长度限制");
        }
        return text;
    }

    /**
     * 校验{@code dEnum}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param allowed {@code allowed}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String requiredEnum(String value, Set<String> allowed, String label) {
        String normalized = value == null ? "" : value.strip();
        if (!allowed.contains(normalized)) {
            throw badRequest(label + "无效");
        }
        return normalized;
    }

    /**
     * 处理{@code badRequest}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException badRequest(String message) {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
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
     * 封装文档Download相关的不可变数据。
     */
    public record DocumentDownload(AgentKnowledgeDocument document, InputStream input) {
    }
}
