package group.aitools.nhs.platform.conversation.service;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.conversation.domain.AgentConversation;
import group.aitools.nhs.platform.conversation.domain.AgentConversationAttachment;
import group.aitools.nhs.platform.conversation.mapper.ConversationTurnMapper;
import group.aitools.nhs.platform.conversation.web.ConversationAttachmentView;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 负责会话附件相关的业务编排与领域规则处理。
 * Validates, stores and reads private conversation attachments. */
@Service
public class ConversationAttachmentService {

    static final long MAX_UPLOAD_BYTES = 10L * 1024 * 1024;
    private static final int MAX_PROMPT_ATTACHMENT_BYTES = 64 * 1024;
    private static final long MAX_RUNTIME_MEDIA_BYTES = 12L * 1024 * 1024;
    private static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "md", "csv", "json");

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final PlatformIdGenerator idGenerator;
    private final ConversationTurnMapper mapper;
    private final ConversationAttachmentStorage storage;
    private final ConversationAttachmentScanService scanService;

    @Autowired
    public ConversationAttachmentService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        PlatformIdGenerator idGenerator,
        ConversationTurnMapper mapper,
        ConversationAttachmentStorage storage,
        ConversationAttachmentScanService scanService
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.idGenerator = idGenerator;
        this.mapper = mapper;
        this.storage = storage;
        this.scanService = scanService;
    }

    /**
     * 创建 {@code ConversationAttachmentService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param authorizationEnforcer 授权Enforcer参数
     * @param idGenerator {@code idGenerator}参数
     * @param mapper {@code mapper}参数
     * @param storage 存储参数
     */
    ConversationAttachmentService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        PlatformIdGenerator idGenerator,
        ConversationTurnMapper mapper,
        ConversationAttachmentStorage storage
    ) {
        this(
            principalProvider, authorizationEnforcer, idGenerator, mapper, storage,
            new ConversationAttachmentScanService("builtin-signature", List.of())
        );
    }

    /**
     * 处理{@code upload}并返回对应结果。
     *
     * @param conversationId 资源标识
     * @param file 文件参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ConversationAttachmentView upload(Long conversationId, MultipartFile file) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentConversation conversation = requireOwnedConversation(conversationId, principal);
        requirePermission(principal, conversation.getId(), "upload_attachment");
        if (file == null || file.isEmpty() || file.getSize() <= 0 || file.getSize() > MAX_UPLOAD_BYTES) {
            throw badRequest("附件为空或超过10MB限制");
        }
        String originalName = safeFileName(file.getOriginalFilename());
        Long attachmentId = idGenerator.nextId();
        ConversationAttachmentStorage.StoredAttachment stored;
        try (InputStream input = file.getInputStream()) {
            stored = storage.put(attachmentId, input, file.getSize());
        } catch (IOException exception) {
            throw badRequest("无法读取上传附件");
        }
        try {
            String detectedMime = validateContent(
                originalName, file.getContentType(), stored.storageRef(), stored.sha256()
            );
            scanService.requireClean(
                originalName, detectedMime, readStoredContent(stored.storageRef())
            );
            AgentConversationAttachment attachment = new AgentConversationAttachment();
            attachment.setId(attachmentId);
            attachment.setConversationId(conversationId);
            attachment.setUserId(principal.id());
            attachment.setOriginalName(originalName);
            attachment.setStorageType("local");
            attachment.setStorageRef(stored.storageRef());
            attachment.setMimeType(detectedMime);
            attachment.setSizeBytes(stored.sizeBytes());
            attachment.setSha256(stored.sha256());
            attachment.setStatus("ready");
            attachment.setCreatedAt(LocalDateTime.now());
            if (mapper.insertAttachment(attachment) != 1) {
                throw conflict("附件元数据写入失败");
            }
            return ConversationAttachmentView.from(attachment);
        } catch (RuntimeException exception) {
            storage.delete(stored.storageRef());
            throw exception;
        }
    }

    /**
     * 查询{@code list}列表。
     *
     * @param conversationId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<ConversationAttachmentView> list(Long conversationId, int limit) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentConversation conversation = requireOwnedConversationForRead(conversationId, principal);
        requirePermission(principal, conversation.getId(), "view");
        return mapper.selectOwnedAttachments(conversationId, principal.id(), limit).stream()
            .map(ConversationAttachmentView::from)
            .toList();
    }

    /**
     * 处理{@code download}并返回对应结果。
     *
     * @param conversationId 资源标识
     * @param attachmentId 资源标识
     * @return 处理结果
     */
    public AttachmentDownload download(Long conversationId, Long attachmentId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentConversation conversation = requireOwnedConversationForRead(conversationId, principal);
        requirePermission(principal, conversation.getId(), "view");
        AgentConversationAttachment attachment = mapper.selectOwnedAttachment(
            conversationId, attachmentId, principal.id()
        );
        if (attachment == null) {
            throw new ServiceException("附件不存在", HttpStatus.NOT_FOUND);
        }
        return new AttachmentDownload(attachment, storage.open(attachment.getStorageRef()));
    }

    /**
     * 校验{@code Ready}，并在条件不满足时终止处理。
     *
     * @param conversationId 资源标识
     * @param userId 资源标识
     * @param attachmentIds 资源标识集合
     * @return 符合条件的数据集合
     */
    List<AgentConversationAttachment> requireReady(
        Long conversationId,
        Long userId,
        List<Long> attachmentIds
    ) {
        List<AgentConversationAttachment> result = new ArrayList<>(attachmentIds.size());
        Set<Long> unique = new java.util.LinkedHashSet<>(attachmentIds);
        if (unique.size() != attachmentIds.size()) {
            throw badRequest("附件ID不能重复");
        }
        for (Long attachmentId : unique) {
            AgentConversationAttachment attachment = mapper.selectOwnedAttachment(
                conversationId, attachmentId, userId
            );
            if (attachment == null || attachment.getTurnId() != null
                || !"ready".equals(attachment.getStatus())) {
                throw new ServiceException("附件不存在或已被其他回合使用", HttpStatus.NOT_FOUND);
            }
            result.add(attachment);
        }
        return List.copyOf(result);
    }

    /**
     * 处理提示词Section并返回对应结果。
     *
     * @param attachments {@code attachments}参数
     * @return 处理结果
     */
    String promptSection(List<AgentConversationAttachment> attachments) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (attachments.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder("\n\n[本回合附件]\n");
        int remaining = MAX_PROMPT_ATTACHMENT_BYTES;
        for (AgentConversationAttachment attachment : attachments) {
            result.append("- ").append(attachment.getOriginalName())
                .append(" (").append(attachment.getMimeType())
                .append(", sha256=").append(attachment.getSha256()).append(")");
            if (isText(attachment.getMimeType()) && remaining > 0) {
                try (InputStream input = storage.open(attachment.getStorageRef())) {
                    byte[] bytes = input.readNBytes(Math.min(remaining, 32 * 1024));
                    String text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
                    result.append("\n```\n").append(text).append("\n```");
                    remaining -= bytes.length;
                } catch (IOException exception) {
                    throw conflict("附件内容在运行前无法读取");
                }
            }
            result.append('\n');
        }
        return result.toString();
    }

    /**
 * 执行{@code timeMedia}相关的处理流程。
 * Loads verified image bytes only for the in-memory model invocation. */
    List<Map<String, Object>> runtimeMedia(List<AgentConversationAttachment> attachments) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        List<Map<String, Object>> result = new ArrayList<>();
        long totalBytes = 0;
        for (AgentConversationAttachment attachment : attachments) {
            if (attachment.getMimeType() == null || !attachment.getMimeType().startsWith("image/")) {
                continue;
            }
            totalBytes += attachment.getSizeBytes();
            if (totalBytes > MAX_RUNTIME_MEDIA_BYTES) {
                throw new ServiceException("图片附件总大小超过12MB限制", 413);
            }
            try (InputStream input = storage.open(attachment.getStorageRef())) {
                byte[] bytes = input.readAllBytes();
                if (bytes.length != attachment.getSizeBytes()
                    || !attachment.getSha256().equals(ContentHashing.sha256(bytes))) {
                    throw conflict("图片附件内容哈希不一致");
                }
                result.add(Map.of(
                    "mimeType", attachment.getMimeType(),
                    "base64", Base64.getEncoder().encodeToString(bytes)
                ));
            } catch (IOException exception) {
                throw conflict("图片附件在运行前无法读取");
            }
        }
        return List.copyOf(result);
    }

    /**
     * 校验Owned会话，并在条件不满足时终止处理。
     *
     * @param conversationId 资源标识
     * @param principal 当前操作主体
     * @return 处理结果
     */
    private AgentConversation requireOwnedConversation(Long conversationId, CurrentPrincipal principal) {
        AgentConversation conversation = mapper.lockOwnedActiveConversation(conversationId, principal.id());
        if (conversation == null) {
            throw new ServiceException("会话不存在", HttpStatus.NOT_FOUND);
        }
        return conversation;
    }

    /**
     * 校验Owned会话ForRead，并在条件不满足时终止处理。
     *
     * @param conversationId 资源标识
     * @param principal 当前操作主体
     * @return 处理结果
     */
    private AgentConversation requireOwnedConversationForRead(
        Long conversationId,
        CurrentPrincipal principal
    ) {
        AgentConversation conversation = mapper.selectOwnedActiveConversation(
            conversationId, principal.id()
        );
        if (conversation == null) {
            throw new ServiceException("会话不存在", HttpStatus.NOT_FOUND);
        }
        return conversation;
    }

    /**
     * 校验权限，并在条件不满足时终止处理。
     *
     * @param principal 当前操作主体
     * @param conversationId 资源标识
     * @param action {@code action}参数
     */
    private void requirePermission(CurrentPrincipal principal, Long conversationId, String action) {
        authorizationEnforcer.requireAllowed(principal, new PermissionContext(
            "conversation", conversationId, null, action,
            ResourceState.ACTIVE, true, Set.of(), null
        ));
    }

    /**
     * 校验{@code Content}，并在条件不满足时终止处理。
     *
     * @param fileName 名称
     * @param declaredContentType 业务类型
     * @param storageRef 存储Ref参数
     * @param expectedHash {@code expectedHash}参数
     * @return 处理结果
     */
    private String validateContent(
        String fileName,
        String declaredContentType,
        String storageRef,
        String expectedHash
    ) {
        byte[] bytes;
        try (InputStream input = storage.open(storageRef)) {
            bytes = input.readAllBytes();
        } catch (IOException exception) {
            throw conflict("附件内容校验失败");
        }
        String actualHash = ContentHashing.sha256(bytes);
        if (!expectedHash.equals(actualHash)) {
            throw conflict("附件内容哈希不一致");
        }
        String extension = extension(fileName);
        String detected = detectMime(extension, bytes);
        String declared = normalizeMime(declaredContentType);
        if (declared != null && !"application/octet-stream".equals(declared)
            && !compatibleMime(declared, detected)) {
            throw badRequest("附件声明类型与实际内容不一致");
        }
        return detected;
    }

    /**
     * 处理{@code readStoredContent}并返回对应结果。
     *
     * @param storageRef 存储Ref参数
     * @return 处理结果
     */
    private byte[] readStoredContent(String storageRef) {
        try (InputStream input = storage.open(storageRef)) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw conflict("附件病毒扫描前无法读取内容");
        }
    }

    /**
     * 处理{@code detectMime}并返回对应结果。
     *
     * @param extension {@code extension}参数
     * @param bytes {@code bytes}参数
     * @return 处理结果
     */
    private String detectMime(String extension, byte[] bytes) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (TEXT_EXTENSIONS.contains(extension)) {
            validateUtf8(bytes);
            return switch (extension) {
                case "md" -> "text/markdown";
                case "csv" -> "text/csv";
                case "json" -> "application/json";
                default -> "text/plain";
            };
        }
        if ("pdf".equals(extension) && startsWith(bytes, "%PDF-".getBytes(StandardCharsets.US_ASCII))) {
            return "application/pdf";
        }
        if ("png".equals(extension) && startsWith(bytes, HexFormat.of().parseHex("89504e470d0a1a0a"))) {
            return "image/png";
        }
        if (("jpg".equals(extension) || "jpeg".equals(extension))
            && bytes.length >= 3 && (bytes[0] & 0xff) == 0xff
            && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        if ("webp".equals(extension) && bytes.length >= 12
            && startsWith(bytes, "RIFF".getBytes(StandardCharsets.US_ASCII))
            && new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) {
            return "image/webp";
        }
        throw badRequest("附件扩展名或实际内容类型不在允许范围");
    }

    /**
     * 校验{@code Utf8}，并在条件不满足时终止处理。
     *
     * @param bytes {@code bytes}参数
     */
    private void validateUtf8(byte[] bytes) {
        for (byte value : bytes) {
            if (value == 0) {
                throw badRequest("文本附件包含NUL字符");
            }
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes));
        } catch (CharacterCodingException exception) {
            throw badRequest("文本附件不是有效UTF-8");
        }
    }

    /**
     * 处理{@code startsWith}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param prefix {@code prefix}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (value[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 处理{@code compatibleMime}并返回对应结果。
     *
     * @param declared {@code declared}参数
     * @param detected {@code detected}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean compatibleMime(String declared, String detected) {
        if (declared.equals(detected)) {
            return true;
        }
        return isText(declared) && isText(detected);
    }

    /**
     * 判断{@code Text}是否满足要求。
     *
     * @param mime {@code mime}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isText(String mime) {
        return mime.startsWith("text/") || "application/json".equals(mime);
    }

    /**
     * 处理{@code normalizeMime}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalizeMime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")) {
            throw badRequest("附件Content-Type无效");
        }
        return normalized;
    }

    /**
     * 处理safe文件Name并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String safeFileName(String value) {
        if (value == null || value.isBlank()) {
            throw badRequest("附件文件名不能为空");
        }
        String normalized = value.strip();
        if (normalized.length() > 255 || normalized.contains("/") || normalized.contains("\\")
            || normalized.equals(".") || normalized.equals("..")
            || normalized.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw badRequest("附件文件名无效");
        }
        extension(normalized);
        return normalized;
    }

    /**
     * 处理{@code extension}并返回对应结果。
     *
     * @param fileName 名称
     * @return 处理结果
     */
    private String extension(String fileName) {
        int separator = fileName.lastIndexOf('.');
        if (separator <= 0 || separator == fileName.length() - 1) {
            throw badRequest("附件必须包含允许的扩展名");
        }
        return fileName.substring(separator + 1).toLowerCase(Locale.ROOT);
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
     * 封装附件Download相关的不可变数据。
     */
    public record AttachmentDownload(AgentConversationAttachment attachment, InputStream input) {
    }
}
