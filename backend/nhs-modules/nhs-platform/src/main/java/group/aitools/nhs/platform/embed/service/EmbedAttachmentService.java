package group.aitools.nhs.platform.embed.service;

import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.conversation.domain.AgentConversationAttachment;
import group.aitools.nhs.platform.conversation.service.ConversationAttachmentScanService;
import group.aitools.nhs.platform.conversation.service.ConversationAttachmentStorage;
import group.aitools.nhs.platform.conversation.web.ConversationAttachmentView;
import group.aitools.nhs.platform.embed.domain.EmbedSession;
import group.aitools.nhs.platform.embed.mapper.EmbedChatMapper;
import group.aitools.nhs.platform.identity.service.AuthenticatedServiceAccount;
import group.aitools.nhs.platform.knowledge.service.KnowledgeDocumentParser;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 负责嵌入式会话附件相关的业务编排与领域规则处理。
 * Bounded, content-validated attachments for one isolated Embed session. */
@Service
public class EmbedAttachmentService {

    public static final long MAX_UPLOAD_BYTES = 10L * 1024 * 1024;
    private static final int MAX_PROMPT_BYTES = 48 * 1024;
    private static final int MAX_CONTEXT_BYTES = 16 * 1024;
    private static final long MAX_MEDIA_BYTES = 12L * 1024 * 1024;
    private static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "md", "csv", "json");

    private final EmbedChatMapper mapper;
    private final PlatformIdGenerator idGenerator;
    private final ConversationAttachmentStorage storage;
    private final JsonMapper jsonMapper;
    private final KnowledgeDocumentParser documentParser;
    private final ConversationAttachmentScanService scanService;

    @Autowired
    public EmbedAttachmentService(
        EmbedChatMapper mapper,
        PlatformIdGenerator idGenerator,
        ConversationAttachmentStorage storage,
        JsonMapper jsonMapper,
        KnowledgeDocumentParser documentParser,
        ConversationAttachmentScanService scanService
    ) {
        this.mapper = mapper;
        this.idGenerator = idGenerator;
        this.storage = storage;
        this.jsonMapper = jsonMapper;
        this.documentParser = documentParser;
        this.scanService = scanService;
    }

    /**
     * 创建 {@code EmbedAttachmentService} 实例并初始化所需依赖。
     *
     * @param mapper {@code mapper}参数
     * @param idGenerator {@code idGenerator}参数
     * @param storage 存储参数
     * @param jsonMapper {@code jsonMapper}参数
     */
    EmbedAttachmentService(
        EmbedChatMapper mapper,
        PlatformIdGenerator idGenerator,
        ConversationAttachmentStorage storage,
        JsonMapper jsonMapper
    ) {
        this(
            mapper, idGenerator, storage, jsonMapper, new KnowledgeDocumentParser(),
            new ConversationAttachmentScanService("builtin-signature", List.of())
        );
    }

    /**
     * 创建 {@code EmbedAttachmentService} 实例并初始化所需依赖。
     *
     * @param mapper {@code mapper}参数
     * @param idGenerator {@code idGenerator}参数
     * @param storage 存储参数
     * @param jsonMapper {@code jsonMapper}参数
     * @param documentParser 文档Parser参数
     */
    EmbedAttachmentService(
        EmbedChatMapper mapper,
        PlatformIdGenerator idGenerator,
        ConversationAttachmentStorage storage,
        JsonMapper jsonMapper,
        KnowledgeDocumentParser documentParser
    ) {
        this(
            mapper, idGenerator, storage, jsonMapper, documentParser,
            new ConversationAttachmentScanService("builtin-signature", List.of())
        );
    }

    /**
     * 处理{@code upload}并返回对应结果。
     *
     * @param authenticated {@code authenticated}参数
     * @param sessionId 资源标识
     * @param file 文件参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ConversationAttachmentView upload(
        AuthenticatedServiceAccount authenticated,
        Long sessionId,
        MultipartFile file
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        EmbedSession session = requireSession(authenticated, sessionId);
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
            String mime = validateContent(originalName, file.getContentType(), stored);
            scanService.requireClean(originalName, mime, readStoredContent(stored.storageRef()));
            AgentConversationAttachment attachment = new AgentConversationAttachment();
            attachment.setId(attachmentId);
            attachment.setConversationId(session.getConversationId());
            attachment.setUserId(authenticated.principal().id());
            attachment.setOriginalName(originalName);
            attachment.setStorageType("local");
            attachment.setStorageRef(stored.storageRef());
            attachment.setMimeType(mime);
            attachment.setSizeBytes(stored.sizeBytes());
            attachment.setSha256(stored.sha256());
            attachment.setStatus("ready");
            attachment.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
            if (mapper.insertAttachment(attachment) != 1) {
                throw conflict("Embed附件元数据写入失败");
            }
            return ConversationAttachmentView.from(attachment);
        } catch (RuntimeException exception) {
            storage.delete(stored.storageRef());
            throw exception;
        }
    }

    /**
     * 处理{@code prepareRequest}并返回对应结果。
     *
     * @param input {@code input}参数
     * @param requestedIds 资源标识集合
     * @param requestedContext 待处理内容
     * @return 处理结果
     */
    public PreparedRequest prepareRequest(
        String input,
        List<Long> requestedIds,
        Map<String, Object> requestedContext
    ) {
        List<Long> attachmentIds = requestedIds == null ? List.of() : List.copyOf(requestedIds);
        if (attachmentIds.size() > 5 || new LinkedHashSet<>(attachmentIds).size() != attachmentIds.size()) {
            throw badRequest("每条消息最多包含5个且不能重复的附件");
        }
        Map<String, Object> context = context(requestedContext);
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("input", input);
        canonical.put("context", context);
        canonical.put("attachmentIds", attachmentIds);
        return new PreparedRequest(
            input, context, attachmentIds, jsonMapper.writeValueAsString(canonical)
        );
    }

    /**
     * 处理{@code prepare}并返回对应结果。
     *
     * @param authenticated {@code authenticated}参数
     * @param session 会话参数
     * @param request 请求参数
     * @return 处理结果
     */
    public PreparedMessage prepare(
        AuthenticatedServiceAccount authenticated,
        EmbedSession session,
        PreparedRequest request
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        List<AgentConversationAttachment> attachments = new ArrayList<>();
        for (Long attachmentId : request.attachmentIds()) {
            if (attachmentId == null || attachmentId <= 0) {
                throw badRequest("附件ID无效");
            }
            AgentConversationAttachment attachment = mapper.selectAttachment(
                session.getConversationId(), attachmentId, authenticated.principal().id()
            );
            if (attachment == null) {
                throw new ServiceException("Embed附件不存在或已被使用", HttpStatus.NOT_FOUND);
            }
            attachments.add(attachment);
        }
        StringBuilder runtime = new StringBuilder(request.input() == null ? "" : request.input());
        if (!request.context().isEmpty()) {
            runtime.append("\n\n[宿主页面上下文]\n")
                .append(jsonMapper.writeValueAsString(request.context()));
        }
        runtime.append(promptSection(attachments));
        List<Map<String, Object>> metadata = attachments.stream().map(attachment -> Map.<String, Object>of(
            "id", attachment.getId(),
            "name", attachment.getOriginalName(),
            "mimeType", attachment.getMimeType(),
            "sizeBytes", attachment.getSizeBytes(),
            "sha256", attachment.getSha256()
        )).toList();
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("source", "embed");
        content.put("context", request.context());
        content.put("attachments", metadata);
        return new PreparedMessage(
            request.input(), runtime.toString(), jsonMapper.writeValueAsString(content),
            request.requestMaterial(), request.attachmentIds(), runtimeMedia(attachments)
        );
    }

    /**
     * 处理{@code attach}相关逻辑。
     *
     * @param session 会话参数
     * @param userId 资源标识
     * @param turnId 资源标识
     * @param attachmentIds 资源标识集合
     */
    public void attach(EmbedSession session, Long userId, Long turnId, List<Long> attachmentIds) {
        for (Long attachmentId : attachmentIds) {
            if (mapper.attachFile(session.getConversationId(), attachmentId, userId, turnId) != 1) {
                throw conflict("Embed附件绑定冲突");
            }
        }
    }

    /**
     * 校验会话，并在条件不满足时终止处理。
     *
     * @param authenticated {@code authenticated}参数
     * @param sessionId 资源标识
     * @return 处理结果
     */
    private EmbedSession requireSession(
        AuthenticatedServiceAccount authenticated,
        Long sessionId
    ) {
        EmbedSession session = mapper.selectSession(sessionId);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (session == null || !authenticated.applicationId().equals(session.getApplicationId())
            || !authenticated.principal().id().equals(session.getServiceAccountId())) {
            throw new ServiceException("Embed会话不存在", HttpStatus.NOT_FOUND);
        }
        if (!"active".equals(session.getStatus()) || !session.getExpiresAt().isAfter(now)) {
            throw conflict("Embed会话已关闭或过期");
        }
        return session;
    }

    /**
     * 处理上下文并返回对应结果。
     *
     * @param requested {@code requested}参数
     * @return 处理结果
     */
    private Map<String, Object> context(Map<String, Object> requested) {
        if (requested == null || requested.isEmpty()) {
            return Map.of();
        }
        if (requested.size() > 32) {
            throw badRequest("Embed上下文字段不能超过32个");
        }
        validateValue(requested, 0);
        String json = jsonMapper.writeValueAsString(requested);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_CONTEXT_BYTES) {
            throw badRequest("Embed上下文超过16KB限制");
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(requested));
    }

    /**
     * 校验{@code Value}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param depth {@code depth}参数
     */
    private void validateValue(Object value, int depth) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (depth > 4) {
            throw badRequest("Embed上下文嵌套超过4层");
        }
        if (value == null || value instanceof Boolean || value instanceof Number) {
            return;
        }
        if (value instanceof String text) {
            if (text.length() > 4096 || text.indexOf('\0') >= 0) {
                throw badRequest("Embed上下文文本过长或包含非法字符");
            }
            return;
        }
        if (value instanceof List<?> list) {
            if (list.size() > 100) {
                throw badRequest("Embed上下文数组过长");
            }
            list.forEach(item -> validateValue(item, depth + 1));
            return;
        }
        if (value instanceof Map<?, ?> map) {
            if (map.size() > 32) {
                throw badRequest("Embed上下文对象字段过多");
            }
            map.forEach((key, item) -> {
                if (!(key instanceof String text) || text.isBlank() || text.length() > 128) {
                    throw badRequest("Embed上下文字段名无效");
                }
                validateValue(item, depth + 1);
            });
            return;
        }
        throw badRequest("Embed上下文包含不支持的数据类型");
    }

    /**
     * 处理提示词Section并返回对应结果。
     *
     * @param attachments {@code attachments}参数
     * @return 处理结果
     */
    private String promptSection(List<AgentConversationAttachment> attachments) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (attachments.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder("\n\n[本回合附件]\n");
        int remaining = MAX_PROMPT_BYTES;
        for (AgentConversationAttachment attachment : attachments) {
            result.append("- ").append(attachment.getOriginalName())
                .append(" (").append(attachment.getMimeType())
                .append(", sha256=").append(attachment.getSha256()).append(")");
            if (isText(attachment.getMimeType()) && remaining > 0) {
                String clipped = clipUtf8(textContent(attachment), Math.min(remaining, 24 * 1024));
                result.append("\n```\n").append(clipped).append("\n```");
                remaining -= clipped.getBytes(StandardCharsets.UTF_8).length;
            } else if ("application/pdf".equals(attachment.getMimeType()) && remaining > 0) {
                String clipped = clipUtf8(textContent(attachment), Math.min(remaining, 24 * 1024));
                if (!clipped.isBlank()) {
                    result.append("\n```\n").append(clipped).append("\n```");
                    remaining -= clipped.getBytes(StandardCharsets.UTF_8).length;
                }
            }
            result.append('\n');
        }
        return result.toString();
    }

    /**
     * 处理{@code textContent}并返回对应结果。
     *
     * @param attachment 附件参数
     * @return 处理结果
     */
    private String textContent(AgentConversationAttachment attachment) {
        try (InputStream input = storage.open(attachment.getStorageRef())) {
            if ("application/pdf".equals(attachment.getMimeType())) {
                return documentParser.parse(
                    input, attachment.getOriginalName(), attachment.getMimeType()
                ).content();
            }
            byte[] bytes = input.readAllBytes();
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (IOException | RuntimeException exception) {
            throw conflict("Embed附件内容在运行前无法读取");
        }
    }

    /**
     * 处理{@code clipUtf8}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param maximumBytes {@code maximumBytes}参数
     * @return 处理结果
     */
    private String clipUtf8(String value, int maximumBytes) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maximumBytes) return value;
        int length = maximumBytes;
        while (length > 0) {
            try {
                return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, length)).toString().stripTrailing();
            } catch (CharacterCodingException ignored) {
                length--;
            }
        }
        return "";
    }

    /**
     * 执行{@code timeMedia}相关的处理流程。
     *
     * @param attachments {@code attachments}参数
     * @return 符合条件的数据集合
     */
    private List<RuntimeMedia> runtimeMedia(List<AgentConversationAttachment> attachments) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        List<RuntimeMedia> result = new ArrayList<>();
        long total = 0;
        for (AgentConversationAttachment attachment : attachments) {
            if (!attachment.getMimeType().startsWith("image/")) continue;
            total += attachment.getSizeBytes();
            if (total > MAX_MEDIA_BYTES) {
                throw badRequest("图片附件总大小超过12MB限制");
            }
            try (InputStream input = storage.open(attachment.getStorageRef())) {
                byte[] bytes = input.readAllBytes();
                if (bytes.length != attachment.getSizeBytes()
                    || !attachment.getSha256().equals(ContentHashing.sha256(bytes))) {
                    throw conflict("Embed图片附件内容哈希不一致");
                }
                result.add(new RuntimeMedia(
                    attachment.getMimeType(), Base64.getEncoder().encodeToString(bytes)
                ));
            } catch (IOException exception) {
                throw conflict("Embed图片附件在运行前无法读取");
            }
        }
        return List.copyOf(result);
    }

    /**
     * 校验{@code Content}，并在条件不满足时终止处理。
     *
     * @param fileName 名称
     * @param declaredContentType 业务类型
     * @param stored {@code stored}参数
     * @return 处理结果
     */
    private String validateContent(
        String fileName,
        String declaredContentType,
        ConversationAttachmentStorage.StoredAttachment stored
    ) {
        byte[] bytes;
        try (InputStream input = storage.open(stored.storageRef())) {
            bytes = input.readAllBytes();
        } catch (IOException exception) {
            throw conflict("Embed附件内容校验失败");
        }
        if (!stored.sha256().equals(ContentHashing.sha256(bytes))) {
            throw conflict("Embed附件内容哈希不一致");
        }
        String extension = extension(fileName);
        String detected = detectMime(extension, bytes);
        String declared = normalizeMime(declaredContentType);
        if (declared != null && !"application/octet-stream".equals(declared)
            && !declared.equals(detected) && !(isText(declared) && isText(detected))) {
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
            throw conflict("Embed附件病毒扫描前无法读取内容");
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
        if (("jpg".equals(extension) || "jpeg".equals(extension)) && bytes.length >= 3
            && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8
            && (bytes[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        if ("webp".equals(extension) && bytes.length >= 12
            && startsWith(bytes, "RIFF".getBytes(StandardCharsets.US_ASCII))
            && "WEBP".equals(new String(bytes, 8, 4, StandardCharsets.US_ASCII))) {
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
                .decode(ByteBuffer.wrap(bytes));
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
        if (value.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if (value[index] != prefix[index]) return false;
        }
        return true;
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
        if (value == null || value.isBlank()) return null;
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
        if (value == null || value.isBlank()) throw badRequest("附件文件名不能为空");
        String normalized = value.strip();
        if (normalized.length() > 255 || normalized.contains("/") || normalized.contains("\\")
            || normalized.equals(".") || normalized.equals("..")
            || normalized.chars().anyMatch(Character::isISOControl)) {
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
     * 封装{@code Prepared}相关的不可变数据。
     */
    public record PreparedRequest(
        String input,
        Map<String, Object> context,
        List<Long> attachmentIds,
        String requestMaterial
    ) {
    }

    /**
     * 封装Prepared消息相关的不可变数据。
     */
    public record PreparedMessage(
        String input,
        String runtimeInput,
        String contentJson,
        String requestMaterial,
        List<Long> attachmentIds,
        List<RuntimeMedia> media
    ) {
        /**
         * 创建 {@code PreparedMessage} 实例并初始化所需依赖。
         *
         * @param input {@code input}参数
         * @param runtimeInput 运行时Input参数
         * @param contentJson 待处理内容
         * @param requestMaterial {@code requestMaterial}参数
         * @param attachmentIds 资源标识集合
         */
        public PreparedMessage(
            String input,
            String runtimeInput,
            String contentJson,
            String requestMaterial,
            List<Long> attachmentIds
        ) {
            this(input, runtimeInput, contentJson, requestMaterial, attachmentIds, List.of());
        }
    }

    /**
     * 封装运行时Media相关的不可变数据。
     */
    public record RuntimeMedia(String mimeType, String base64) {
    }
}
