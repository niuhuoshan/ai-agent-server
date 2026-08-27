package group.aitools.nhs.platform.conversation.service;

import group.aitools.nhs.platform.conversation.web.ConversationMessageView;
import group.aitools.nhs.platform.conversation.web.ConversationView;
import group.aitools.nhs.platform.execution.service.ExecutionEventQueryService;
import group.aitools.nhs.platform.execution.web.ExecutionEventView;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 负责会话导出相关的业务编排与领域规则处理。
 * Produces owner-authorized, reproducible conversation exports. */
@Service
public class ConversationExportService {

    private static final int MAX_EXPORT_BYTES = 20 * 1024 * 1024;

    private final ConversationApplicationService conversationService;
    private final ExecutionEventQueryService eventQueryService;
    private final JsonMapper jsonMapper;

    public ConversationExportService(
        ConversationApplicationService conversationService,
        ExecutionEventQueryService eventQueryService,
        JsonMapper jsonMapper
    ) {
        this.conversationService = conversationService;
        this.eventQueryService = eventQueryService;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 处理导出并返回对应结果。
     *
     * @param conversationId 资源标识
     * @param requestedFormat {@code requestedFormat}参数
     * @return 处理结果
     */
    public ConversationExport export(Long conversationId, String requestedFormat) {
        String format = requestedFormat == null ? "markdown" : requestedFormat.strip().toLowerCase(Locale.ROOT);
        if ("md".equals(format)) {
            format = "markdown";
        }
        if (!List.of("json", "markdown").contains(format)) {
            throw new ServiceException("仅支持 json 或 markdown 导出", HttpStatus.BAD_REQUEST);
        }
        ConversationView conversation = conversationService.get(conversationId);
        List<ConversationMessageView> messages = conversationService.messages(conversationId, 0, 500);
        List<ExecutionEventView> events = eventQueryService.listConversation(conversationId, 0, 1000);
        byte[] content = "json".equals(format)
            ? json(conversation, messages, events)
            : markdown(conversation, messages, events).getBytes(StandardCharsets.UTF_8);
        if (content.length > MAX_EXPORT_BYTES) {
            throw new ServiceException("会话导出超过20MB限制", 413);
        }
        String extension = "json".equals(format) ? "json" : "md";
        String mediaType = "json".equals(format) ? "application/json" : "text/markdown";
        return new ConversationExport(
            "conversation-" + conversationId + "." + extension,
            mediaType + ";charset=UTF-8",
            content
        );
    }

    /**
     * 处理{@code json}并返回对应结果。
     *
     * @param conversation 会话参数
     * @param messages 待处理内容
     * @param events {@code events}参数
     * @return 处理结果
     */
    private byte[] json(
        ConversationView conversation,
        List<ConversationMessageView> messages,
        List<ExecutionEventView> events
    ) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schemaVersion", 1);
        document.put("conversation", conversation);
        document.put("messages", messages);
        document.put("events", events);
        try {
            return jsonMapper.writeValueAsString(document).getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException exception) {
            throw new ServiceException("会话导出序列化失败", HttpStatus.ERROR);
        }
    }

    /**
     * 处理{@code markdown}并返回对应结果。
     *
     * @param conversation 会话参数
     * @param messages 待处理内容
     * @param events {@code events}参数
     * @return 处理结果
     */
    private String markdown(
        ConversationView conversation,
        List<ConversationMessageView> messages,
        List<ExecutionEventView> events
    ) {
        StringBuilder value = new StringBuilder();
        value.append("# ").append(conversation.title() == null ? "会话" : conversation.title()).append("\n\n")
            .append("- 会话 ID: ").append(conversation.id()).append("\n")
            .append("- 创建时间: ").append(conversation.createdAt()).append("\n")
            .append("- 最后消息时间: ").append(conversation.lastMessageAt()).append("\n\n")
            .append("## 消息\n\n");
        for (ConversationMessageView message : messages) {
            value.append("### ").append(roleLabel(message.role())).append("\n\n")
                .append(message.content() == null ? "" : message.content()).append("\n\n")
                .append("`sequence=").append(message.sequenceNo());
            if (message.traceId() != null) {
                value.append(" trace=").append(message.traceId());
            }
            value.append("`\n\n");
        }
        value.append("## 执行事件\n\n");
        for (ExecutionEventView event : events) {
            value.append("- `").append(event.cursor()).append("` ")
                .append(event.eventType()).append(" [").append(event.eventStatus()).append("] ")
                .append(event.summary() == null ? "" : event.summary().replace('\n', ' ')).append("\n");
        }
        return value.toString();
    }

    /**
     * 处理角色Label并返回对应结果。
     *
     * @param role 角色参数
     * @return 处理结果
     */
    private String roleLabel(String role) {
        return switch (role == null ? "" : role) {
            case "user" -> "用户";
            case "assistant" -> "助手";
            case "tool" -> "工具";
            case "system" -> "系统";
            default -> role == null ? "消息" : role;
        };
    }

    /**
     * 封装会话导出相关的不可变数据。
     */
    public record ConversationExport(String fileName, String mediaType, byte[] content) {
    }
}
