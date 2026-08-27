package group.aitools.nhs.platform.canvas.service;

import group.aitools.nhs.platform.canvas.domain.AgentConversationCanvas;
import group.aitools.nhs.platform.canvas.domain.AgentConversationCanvasVersion;
import group.aitools.nhs.platform.canvas.mapper.ConversationCanvasMapper;
import group.aitools.nhs.platform.canvas.web.CanvasVersionView;
import group.aitools.nhs.platform.canvas.web.CanvasView;
import group.aitools.nhs.platform.canvas.web.CanvasWorkspaceSaveView;
import group.aitools.nhs.platform.canvas.web.CreateCanvasRequest;
import group.aitools.nhs.platform.canvas.web.RestoreCanvasVersionRequest;
import group.aitools.nhs.platform.canvas.web.SaveCanvasToWorkspaceRequest;
import group.aitools.nhs.platform.canvas.web.UpdateCanvasRequest;
import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.conversation.domain.AgentConversation;
import group.aitools.nhs.platform.conversation.mapper.AgentConversationMapper;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.nhs.service.NhsWorkspaceService;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 负责会话画布相关的业务编排与领域规则处理。
 * Complete owner-bound Canvas lifecycle with immutable versions and workspace export. */
@Service
public class ConversationCanvasService {

    private static final int MAX_CONTENT_BYTES = 10 * 1024 * 1024;
    private static final int MAX_METADATA_BYTES = 64 * 1024;
    private static final int MAX_METADATA_DEPTH = 6;
    private static final int MAX_METADATA_ENTRIES = 128;
    private static final Set<String> CONTENT_TYPES = Set.of(
        "markdown", "html", "code", "mermaid", "pdf", "csv", "image", "compare"
    );
    private static final Set<String> ENCODINGS = Set.of(
        "text", "data-url", "base64", "url", "canvas-uri"
    );
    private static final Pattern METADATA_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,63}");
    private static final Set<String> SENSITIVE_KEY_PARTS = Set.of(
        "password", "secret", "token", "authorization", "credential", "apikey", "privatekey"
    );
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final CurrentPrincipalProvider principalProvider;
    private final AgentConversationMapper conversationMapper;
    private final ConversationCanvasMapper canvasMapper;
    private final PlatformIdGenerator idGenerator;
    private final JsonMapper jsonMapper;
    private final CanvasActionAuditService auditService;
    private final NhsWorkspaceService workspaceService;

    /**
     * 创建 {@code ConversationCanvasService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param conversationMapper 会话Mapper参数
     * @param canvasMapper 画布Mapper参数
     * @param idGenerator {@code idGenerator}参数
     * @param jsonMapper {@code jsonMapper}参数
     * @param auditService 审计Service参数
     * @param workspaceService 工作空间Service参数
     */
    public ConversationCanvasService(
        CurrentPrincipalProvider principalProvider,
        AgentConversationMapper conversationMapper,
        ConversationCanvasMapper canvasMapper,
        PlatformIdGenerator idGenerator,
        JsonMapper jsonMapper,
        CanvasActionAuditService auditService,
        NhsWorkspaceService workspaceService
    ) {
        this.principalProvider = principalProvider;
        this.conversationMapper = conversationMapper;
        this.canvasMapper = canvasMapper;
        this.idGenerator = idGenerator;
        this.jsonMapper = jsonMapper;
        this.auditService = auditService;
        this.workspaceService = workspaceService;
    }

    /**
     * 查询{@code list}列表。
     *
     * @param conversationId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<CanvasView> list(Long conversationId, int limit) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        return audited(principal, "list", conversationId, null, "limit=" + limit, () -> {
            requireHuman(principal);
            requireConversation(conversationId, principal);
            if (limit < 1 || limit > 100) {
                throw badRequest("画布列表数量必须在1到100之间");
            }
            return canvasMapper.selectOwnedCanvases(conversationId, principal.id(), limit).stream()
                .map(this::view)
                .toList();
        });
    }

    /**
     * 获取{@code get}。
     *
     * @param conversationId 资源标识
     * @param canvasId 资源标识
     * @return 处理结果
     */
    public CanvasView get(Long conversationId, Long canvasId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        return audited(principal, "view", conversationId, canvasId, null, () ->
            view(requireCanvas(conversationId, canvasId, principal))
        );
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param conversationId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public CanvasView create(Long conversationId, CreateCanvasRequest request) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        Long canvasId = idGenerator.nextId();
        return audited(principal, "create", conversationId, canvasId, null, () -> {
            requireHuman(principal);
            requireConversation(conversationId, principal);
            ValidatedCanvas validated = validate(
                request.title(), request.contentType(), request.content(), request.metadata()
            );
            LocalDateTime now = LocalDateTime.now();
            AgentConversationCanvas canvas = new AgentConversationCanvas();
            canvas.setId(canvasId);
            canvas.setConversationId(conversationId);
            canvas.setOwnerId(principal.id());
            canvas.setTitle(validated.title());
            canvas.setCanvasType(validated.contentType());
            canvas.setCurrentVersionNo(1);
            canvas.setRevisionNo(1);
            canvas.setMetadataJson(validated.metadataJson());
            canvas.setContentSize(validated.contentSize());
            canvas.setContentSha256(validated.contentSha256());
            canvas.setContent(validated.content());
            canvas.setCreateBy(principal.id());
            canvas.setCreateTime(now);
            canvas.setUpdateBy(principal.id());
            canvas.setUpdateTime(now);
            canvas.setDelFlag("0");
            requireInserted(canvasMapper.insertCanvas(canvas), "创建画布失败");
            requireInserted(canvasMapper.insertVersion(version(
                canvasId, 1, validated, "created", null, principal.id(), now
            )), "创建画布版本失败");
            return view(canvas);
        });
    }

    /**
     * 更新{@code update}。
     *
     * @param conversationId 资源标识
     * @param canvasId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public CanvasView update(Long conversationId, Long canvasId, UpdateCanvasRequest request) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        return audited(principal, "update", conversationId, canvasId,
            "expectedVersion=" + request.expectedVersion(), () -> {
                AgentConversationCanvas current = requireCanvas(conversationId, canvasId, principal);
                requireExpectedVersion(current, request.expectedVersion());
                ValidatedCanvas validated = validate(
                    request.title(), request.contentType(), request.content(), request.metadata()
                );
                int nextVersion = nextVersion(request.expectedVersion());
                LocalDateTime now = LocalDateTime.now();
                int changed = canvasMapper.advanceVersion(
                    conversationId, canvasId, principal.id(), request.expectedVersion(), nextVersion,
                    validated.title(), validated.contentType(), validated.metadataJson(),
                    validated.contentSize(), validated.contentSha256(), now
                );
                requireAdvanced(changed);
                requireInserted(canvasMapper.insertVersion(version(
                    canvasId, nextVersion, validated, "updated", null, principal.id(), now
                )), "保存画布版本失败");
                apply(current, validated, nextVersion, now, principal.id());
                return view(current);
            }
        );
    }

    /**
     * 处理{@code versions}并返回对应结果。
     *
     * @param conversationId 资源标识
     * @param canvasId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<CanvasVersionView> versions(Long conversationId, Long canvasId, int limit) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        return audited(principal, "versions", conversationId, canvasId, "limit=" + limit, () -> {
            requireCanvas(conversationId, canvasId, principal);
            if (limit < 1 || limit > 200) {
                throw badRequest("画布版本数量必须在1到200之间");
            }
            return canvasMapper.selectOwnedVersions(
                conversationId, canvasId, principal.id(), limit
            ).stream().map(this::versionView).toList();
        });
    }

    /**
     * 处理{@code restore}并返回对应结果。
     *
     * @param conversationId 资源标识
     * @param canvasId 资源标识
     * @param versionNo 版本No参数
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public CanvasView restore(
        Long conversationId,
        Long canvasId,
        int versionNo,
        RestoreCanvasVersionRequest request
    ) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        return audited(principal, "restore", conversationId, canvasId,
            "sourceVersion=" + versionNo + ",expectedVersion=" + request.expectedVersion(), () -> {
                AgentConversationCanvas current = requireCanvas(conversationId, canvasId, principal);
                requireExpectedVersion(current, request.expectedVersion());
                if (versionNo == current.getCurrentVersionNo()) {
                    throw conflict("目标版本已经是当前版本");
                }
                AgentConversationCanvasVersion source = canvasMapper.selectOwnedVersion(
                    conversationId, canvasId, principal.id(), versionNo
                );
                if (source == null) {
                    throw notFound("画布版本不存在");
                }
                ValidatedCanvas restored = validate(
                    source.getTitle(), source.getCanvasType(), source.getContent(),
                    parseMetadata(source.getMetadataJson())
                );
                int nextVersion = nextVersion(request.expectedVersion());
                LocalDateTime now = LocalDateTime.now();
                int changed = canvasMapper.advanceVersion(
                    conversationId, canvasId, principal.id(), request.expectedVersion(), nextVersion,
                    restored.title(), restored.contentType(), restored.metadataJson(),
                    restored.contentSize(), restored.contentSha256(), now
                );
                requireAdvanced(changed);
                requireInserted(canvasMapper.insertVersion(version(
                    canvasId, nextVersion, restored, "restored", versionNo, principal.id(), now
                )), "恢复画布版本失败");
                apply(current, restored, nextVersion, now, principal.id());
                return view(current);
            }
        );
    }

    /**
     * 删除{@code delete}。
     *
     * @param conversationId 资源标识
     * @param canvasId 资源标识
     * @param expectedVersion expected版本参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long conversationId, Long canvasId, int expectedVersion) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        audited(principal, "delete", conversationId, canvasId,
            "expectedVersion=" + expectedVersion, () -> {
                AgentConversationCanvas current = requireCanvas(conversationId, canvasId, principal);
                requireExpectedVersion(current, expectedVersion);
                requireAdvanced(canvasMapper.softDelete(
                    conversationId, canvasId, principal.id(), expectedVersion
                ));
                return null;
            }
        );
    }

    /**
     * 保存To工作空间。
     *
     * @param conversationId 资源标识
     * @param canvasId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    public CanvasWorkspaceSaveView saveToWorkspace(
        Long conversationId,
        Long canvasId,
        SaveCanvasToWorkspaceRequest request
    ) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        return audited(principal, "save_workspace", conversationId, canvasId,
            "expectedVersion=" + request.expectedVersion() + ",overwrite=" + request.overwrite(), () -> {
                AgentConversationCanvas current = requireCanvas(conversationId, canvasId, principal);
                requireExpectedVersion(current, request.expectedVersion());
                Map<String, Object> metadata = parseMetadata(current.getMetadataJson());
                String path = workspacePath(request.path(), current, metadata);
                byte[] bytes = workspaceBytes(current.getContent(), metadata);
                Map<String, Object> saved = workspaceService.writeCanvas(path, bytes, request.overwrite());
                String savedPath = stringValue(saved.get("path"), path);
                return new CanvasWorkspaceSaveView(
                    canvasId,
                    current.getCurrentVersionNo(),
                    savedPath,
                    fileName(savedPath),
                    bytes.length,
                    request.overwrite(),
                    LocalDateTime.now()
                );
            }
        );
    }

    /**
     * 校验画布，并在条件不满足时终止处理。
     *
     * @param conversationId 资源标识
     * @param canvasId 资源标识
     * @param principal 当前操作主体
     * @return 处理结果
     */
    private AgentConversationCanvas requireCanvas(
        Long conversationId,
        Long canvasId,
        CurrentPrincipal principal
    ) {
        requireHuman(principal);
        requireConversation(conversationId, principal);
        AgentConversationCanvas canvas = canvasMapper.selectOwnedCanvas(
            conversationId, canvasId, principal.id()
        );
        if (canvas == null) {
            throw notFound("画布不存在");
        }
        return canvas;
    }

    /**
     * 校验会话，并在条件不满足时终止处理。
     *
     * @param conversationId 资源标识
     * @param principal 当前操作主体
     * @return 处理结果
     */
    private AgentConversation requireConversation(Long conversationId, CurrentPrincipal principal) {
        AgentConversation conversation = conversationMapper.selectOwnedConversation(
            conversationId, principal.id()
        );
        if (conversation == null) {
            throw notFound("会话不存在");
        }
        return conversation;
    }

    /**
     * 校验{@code Human}，并在条件不满足时终止处理。
     *
     * @param principal 当前操作主体
     */
    private void requireHuman(CurrentPrincipal principal) {
        if (!principal.isHuman()) {
            throw new ServiceException("机器主体不能访问个人会话画布", HttpStatus.FORBIDDEN);
        }
    }

    /**
     * 校验{@code validate}，并在条件不满足时终止处理。
     *
     * @param title {@code title}参数
     * @param contentType 业务类型
     * @param content 待处理内容
     * @param metadata 元数据参数
     * @return 处理结果
     */
    private ValidatedCanvas validate(
        String title,
        String contentType,
        String content,
        Map<String, Object> metadata
    ) {
        String normalizedTitle = boundedText(title, 255, "画布标题");
        String normalizedType = contentType == null ? "" : contentType.strip().toLowerCase(Locale.ROOT);
        if (!CONTENT_TYPES.contains(normalizedType)) {
            throw badRequest("画布类型无效");
        }
        if (content == null || content.isEmpty() || content.indexOf('\0') >= 0) {
            throw badRequest("画布内容不能为空或包含非法字符");
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_CONTENT_BYTES) {
            throw badRequest("画布内容超过10MB限制");
        }
        MetadataDocument document = metadata(metadata, normalizedType);
        return new ValidatedCanvas(
            normalizedTitle,
            normalizedType,
            content,
            document.values(),
            document.json(),
            bytes.length,
            ContentHashing.sha256(bytes)
        );
    }

    /**
     * 处理元数据并返回对应结果。
     *
     * @param source 数据源参数
     * @param contentType 业务类型
     * @return 处理结果
     */
    private MetadataDocument metadata(Map<String, Object> source, String contentType) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        Map<String, Object> values = source == null ? Map.of() : new LinkedHashMap<>(source);
        int[] entries = {0};
        validateMetadataValue(values, 0, entries);
        String mimeType = optionalMetadataText(values.get("mimeType"), 128, "mimeType");
        String encoding = optionalMetadataText(values.get("encoding"), 32, "encoding");
        if (encoding != null && !ENCODINGS.contains(encoding.toLowerCase(Locale.ROOT))) {
            throw badRequest("画布元数据 encoding 无效");
        }
        if ("image".equals(contentType) && mimeType != null && !mimeType.startsWith("image/")) {
            throw badRequest("图片画布的 mimeType 必须是 image/*");
        }
        if ("pdf".equals(contentType) && mimeType != null
            && !"application/pdf".equalsIgnoreCase(mimeType)) {
            throw badRequest("PDF画布的 mimeType 必须是 application/pdf");
        }
        optionalMetadataText(values.get("language"), 64, "language");
        optionalMetadataText(values.get("fileName"), 255, "fileName");
        optionalMetadataText(values.get("sourcePath"), 512, "sourcePath");
        optionalMetadataText(values.get("workspacePath"), 512, "workspacePath");
        optionalMetadataText(values.get("sourceRole"), 32, "sourceRole");
        sourceMessageId(values);
        String json = jsonMapper.writeValueAsString(values);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_METADATA_BYTES) {
            throw badRequest("画布元数据超过64KB限制");
        }
        return new MetadataDocument(Collections.unmodifiableMap(values), json);
    }

    /**
     * 校验元数据Value，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param depth {@code depth}参数
     * @param entries {@code entries}参数
     */
    private void validateMetadataValue(Object value, int depth, int[] entries) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (depth > MAX_METADATA_DEPTH) {
            throw badRequest("画布元数据嵌套过深");
        }
        if (value == null || value instanceof Boolean || value instanceof Number) {
            if (value instanceof Double doubleValue && !Double.isFinite(doubleValue)
                || value instanceof Float floatValue && !Float.isFinite(floatValue)) {
                throw badRequest("画布元数据包含非有限数值");
            }
            return;
        }
        if (value instanceof String text) {
            if (text.length() > 8192 || text.indexOf('\0') >= 0) {
                throw badRequest("画布元数据文本过长或包含非法字符");
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key) || !METADATA_KEY.matcher(key).matches()) {
                    throw badRequest("画布元数据字段名无效");
                }
                String compact = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
                if (SENSITIVE_KEY_PARTS.stream().anyMatch(compact::contains)) {
                    throw badRequest("画布元数据不得包含凭证或密钥字段");
                }
                if (++entries[0] > MAX_METADATA_ENTRIES) {
                    throw badRequest("画布元数据字段过多");
                }
                validateMetadataValue(entry.getValue(), depth + 1, entries);
            }
            return;
        }
        if (value instanceof Collection<?> collection) {
            if (collection.size() > MAX_METADATA_ENTRIES) {
                throw badRequest("画布元数据数组过长");
            }
            for (Object item : collection) {
                validateMetadataValue(item, depth + 1, entries);
            }
            return;
        }
        throw badRequest("画布元数据只能包含JSON值");
    }

    /**
     * 处理版本并返回对应结果。
     *
     * @param canvasId 资源标识
     * @param versionNo 版本No参数
     * @param value {@code value}参数
     * @param changeType 业务类型
     * @param sourceVersion 数据源版本参数
     * @param actorId 资源标识
     * @param createdAt {@code createdAt}参数
     * @return 处理结果
     */
    private AgentConversationCanvasVersion version(
        Long canvasId,
        int versionNo,
        ValidatedCanvas value,
        String changeType,
        Integer sourceVersion,
        Long actorId,
        LocalDateTime createdAt
    ) {
        AgentConversationCanvasVersion version = new AgentConversationCanvasVersion();
        version.setId(idGenerator.nextId());
        version.setCanvasId(canvasId);
        version.setVersionNo(versionNo);
        version.setTitle(value.title());
        version.setCanvasType(value.contentType());
        version.setContent(value.content());
        version.setMetadataJson(value.metadataJson());
        version.setContentSize(value.contentSize());
        version.setContentSha256(value.contentSha256());
        version.setChangeType(changeType);
        version.setSourceVersionNo(sourceVersion);
        version.setCreatedBy(actorId);
        version.setCreatedAt(createdAt);
        return version;
    }

    /**
     * 处理{@code apply}相关逻辑。
     *
     * @param canvas 画布参数
     * @param value {@code value}参数
     * @param version 版本参数
     * @param updatedAt {@code updatedAt}参数
     * @param actorId 资源标识
     */
    private void apply(
        AgentConversationCanvas canvas,
        ValidatedCanvas value,
        int version,
        LocalDateTime updatedAt,
        Long actorId
    ) {
        canvas.setTitle(value.title());
        canvas.setCanvasType(value.contentType());
        canvas.setContent(value.content());
        canvas.setMetadataJson(value.metadataJson());
        canvas.setContentSize(value.contentSize());
        canvas.setContentSha256(value.contentSha256());
        canvas.setCurrentVersionNo(version);
        canvas.setRevisionNo(version);
        canvas.setUpdateBy(actorId);
        canvas.setUpdateTime(updatedAt);
    }

    /**
     * 处理{@code view}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private CanvasView view(AgentConversationCanvas value) {
        LocalDateTime updated = value.getUpdateTime() == null ? value.getCreateTime() : value.getUpdateTime();
        Map<String, Object> metadata = parseMetadata(value.getMetadataJson());
        return new CanvasView(
            value.getId(), value.getConversationId(), value.getTitle(), value.getCanvasType(),
            value.getContent(), metadata, workspacePath(metadata), sourceMessageId(metadata),
            value.getCurrentVersionNo(),
            value.getRevisionNo(), value.getContentSize(), value.getContentSha256(),
            value.getCreateTime(), updated
        );
    }

    /**
     * 处理版本View并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private CanvasVersionView versionView(AgentConversationCanvasVersion value) {
        Map<String, Object> metadata = parseMetadata(value.getMetadataJson());
        return new CanvasVersionView(
            value.getId(), value.getCanvasId(), value.getVersionNo(), value.getTitle(),
            value.getCanvasType(), value.getContent(), metadata, workspacePath(metadata),
            value.getContentSize(), value.getContentSha256(), value.getChangeType(),
            value.getSourceVersionNo(), value.getCreatedBy(), value.getCreatedAt()
        );
    }

    /**
     * 处理工作空间Path并返回对应结果。
     *
     * @param metadata 元数据参数
     * @return 处理结果
     */
    private String workspacePath(Map<String, Object> metadata) {
        String value = optionalMetadataText(metadata.get("workspacePath"), 512, "workspacePath");
        return value == null
            ? optionalMetadataText(metadata.get("sourcePath"), 512, "sourcePath") : value;
    }

    /**
     * 处理数据源消息Id并返回对应结果。
     *
     * @param metadata 元数据参数
     * @return 处理结果
     */
    private Long sourceMessageId(Map<String, Object> metadata) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        Object value = metadata.get("sourceMessageId");
        if (value == null) {
            return null;
        }
        if (value instanceof Number number && number.doubleValue() == number.longValue()
            && number.longValue() > 0) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                long parsed = Long.parseLong(text);
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // Fall through to the stable metadata validation error.
            }
        }
        throw badRequest("画布元数据 sourceMessageId 必须是正整数");
    }

    /**
     * 处理parse元数据并返回对应结果。
     *
     * @param json {@code json}参数
     * @return 处理结果
     */
    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> values = jsonMapper.readValue(json, MAP_TYPE);
            return values == null || values.isEmpty()
                ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
        } catch (RuntimeException exception) {
            throw new ServiceException("画布元数据损坏", HttpStatus.ERROR);
        }
    }

    /**
     * 处理工作空间Path并返回对应结果。
     *
     * @param requested {@code requested}参数
     * @param canvas 画布参数
     * @param metadata 元数据参数
     * @return 处理结果
     */
    private String workspacePath(
        String requested,
        AgentConversationCanvas canvas,
        Map<String, Object> metadata
    ) {
        if (requested != null && !requested.isBlank()) {
            return requested.strip();
        }
        String metadataName = optionalMetadataText(metadata.get("fileName"), 255, "fileName");
        String raw = metadataName == null ? canvas.getTitle() : metadataName;
        String safe = raw.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").strip();
        if (safe.isBlank()) {
            safe = "canvas-" + canvas.getId();
        }
        String extension = extension(canvas.getCanvasType(), metadata);
        return safe.toLowerCase(Locale.ROOT).endsWith(extension) ? safe : safe + extension;
    }

    /**
     * 处理{@code extension}并返回对应结果。
     *
     * @param contentType 业务类型
     * @param metadata 元数据参数
     * @return 处理结果
     */
    private String extension(String contentType, Map<String, Object> metadata) {
        if ("code".equals(contentType)) {
            String language = optionalMetadataText(metadata.get("language"), 64, "language");
            if (language != null) {
                return switch (language.toLowerCase(Locale.ROOT)) {
                    case "python", "py" -> ".py";
                    case "javascript", "js" -> ".js";
                    case "typescript", "ts" -> ".ts";
                    case "java" -> ".java";
                    case "shell", "bash", "sh" -> ".sh";
                    case "go" -> ".go";
                    default -> ".txt";
                };
            }
        }
        return switch (contentType) {
            case "markdown" -> ".md";
            case "html" -> ".html";
            case "mermaid" -> ".mmd";
            case "pdf" -> ".pdf";
            case "csv" -> ".csv";
            case "image" -> imageExtension(metadata);
            default -> ".txt";
        };
    }

    /**
     * 处理{@code imageExtension}并返回对应结果。
     *
     * @param metadata 元数据参数
     * @return 处理结果
     */
    private String imageExtension(Map<String, Object> metadata) {
        String mime = optionalMetadataText(metadata.get("mimeType"), 128, "mimeType");
        if (mime == null) {
            return ".png";
        }
        return switch (mime.toLowerCase(Locale.ROOT)) {
            case "image/jpeg" -> ".jpg";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/svg+xml" -> ".svg";
            default -> ".png";
        };
    }

    /**
     * 处理工作空间Bytes并返回对应结果。
     *
     * @param content 待处理内容
     * @param metadata 元数据参数
     * @return 处理结果
     */
    private byte[] workspaceBytes(String content, Map<String, Object> metadata) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        String encoding = optionalMetadataText(metadata.get("encoding"), 32, "encoding");
        String normalized = encoding == null ? "" : encoding.toLowerCase(Locale.ROOT);
        try {
            byte[] bytes;
            if ("data-url".equals(normalized) || normalized.isEmpty() && content.startsWith("data:")) {
                int comma = content.indexOf(',');
                if (comma <= 5 || !content.substring(0, comma).contains(";base64")) {
                    throw badRequest("画布 data URL 编码无效");
                }
                bytes = Base64.getDecoder().decode(content.substring(comma + 1));
            } else if ("base64".equals(normalized)) {
                bytes = Base64.getDecoder().decode(content);
            } else {
                bytes = content.getBytes(StandardCharsets.UTF_8);
            }
            if (bytes.length == 0 || bytes.length > MAX_CONTENT_BYTES) {
                throw badRequest("工作区文件内容为空或超过10MB限制");
            }
            return bytes;
        } catch (IllegalArgumentException exception) {
            throw badRequest("画布 Base64 编码无效");
        }
    }

    /**
     * 校验Expected版本，并在条件不满足时终止处理。
     *
     * @param canvas 画布参数
     * @param expectedVersion expected版本参数
     */
    private void requireExpectedVersion(AgentConversationCanvas canvas, int expectedVersion) {
        if (expectedVersion < 1 || canvas.getCurrentVersionNo() != expectedVersion
            || canvas.getRevisionNo() != expectedVersion) {
            throw conflict("画布版本已变化，请刷新后重试");
        }
    }

    /**
     * 处理next版本并返回对应结果。
     *
     * @param version 版本参数
     * @return 处理结果
     */
    private int nextVersion(int version) {
        if (version == Integer.MAX_VALUE) {
            throw conflict("画布版本号已达上限");
        }
        return version + 1;
    }

    /**
     * 校验{@code Inserted}，并在条件不满足时终止处理。
     *
     * @param count {@code count}参数
     * @param message 待处理内容
     */
    private void requireInserted(int count, String message) {
        if (count != 1) {
            throw new ServiceException(message, HttpStatus.ERROR);
        }
    }

    /**
     * 校验{@code Advanced}，并在条件不满足时终止处理。
     *
     * @param count {@code count}参数
     */
    private void requireAdvanced(int count) {
        if (count != 1) {
            throw conflict("画布版本已变化，请刷新后重试");
        }
    }

    /**
     * 处理{@code boundedText}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param maxLength {@code maxLength}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String boundedText(String value, int maxLength, String label) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > maxLength || normalized.indexOf('\0') >= 0) {
            throw badRequest(label + "为空、过长或包含非法字符");
        }
        return normalized;
    }

    /**
     * 处理optional元数据Text并返回对应结果。
     *
     * @param value {@code value}参数
     * @param maxLength {@code maxLength}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String optionalMetadataText(Object value, int maxLength, String label) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw badRequest("画布元数据 " + label + " 必须是字符串");
        }
        String normalized = text.strip();
        if (normalized.isEmpty() || normalized.length() > maxLength || normalized.indexOf('\0') >= 0) {
            throw badRequest("画布元数据 " + label + " 无效");
        }
        return normalized;
    }

    /**
     * 处理文件Name并返回对应结果。
     *
     * @param path {@code path}参数
     * @return 处理结果
     */
    private String fileName(String path) {
        try {
            Path value = Path.of(path);
            return value.getFileName() == null ? path : value.getFileName().toString();
        } catch (InvalidPathException exception) {
            return path;
        }
    }

    /**
     * 处理{@code stringValue}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param fallback {@code fallback}参数
     * @return 处理结果
     */
    private String stringValue(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    /**
     * 处理{@code audited}并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param action {@code action}参数
     * @param conversationId 资源标识
     * @param canvasId 资源标识
     * @param detail {@code detail}参数
     * @param operation 操作参数
     * @return 处理结果
     */
    private <T> T audited(
        CurrentPrincipal principal,
        String action,
        Long conversationId,
        Long canvasId,
        String detail,
        Supplier<T> operation
    ) {
        String summary = "conversationId=" + conversationId
            + (canvasId == null ? "" : ",canvasId=" + canvasId)
            + (detail == null ? "" : "," + detail);
        try {
            T result = operation.get();
            auditService.record(principal, action, canvasId, "success", "completed", summary);
            return result;
        } catch (ServiceException exception) {
            String decision = exception.getCode() == HttpStatus.FORBIDDEN
                || exception.getCode() == HttpStatus.NOT_FOUND ? "deny" : "failure";
            auditService.record(
                principal, action, canvasId, decision,
                "http_" + exception.getCode() + ":" + exception.getMessage(), summary
            );
            throw exception;
        } catch (RuntimeException exception) {
            auditService.record(
                principal, action, canvasId, "failure",
                "internal:" + exception.getClass().getSimpleName(), summary
            );
            throw exception;
        }
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
     * 处理{@code notFound}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException notFound(String message) {
        return new ServiceException(message, HttpStatus.NOT_FOUND);
    }

    /**
     * 封装元数据文档相关的不可变数据。
     */
    private record MetadataDocument(Map<String, Object> values, String json) {
    }

    /**
     * 封装Validated画布相关的不可变数据。
     */
    private record ValidatedCanvas(
        String title,
        String contentType,
        String content,
        Map<String, Object> metadata,
        String metadataJson,
        long contentSize,
        String contentSha256
    ) {
    }
}
