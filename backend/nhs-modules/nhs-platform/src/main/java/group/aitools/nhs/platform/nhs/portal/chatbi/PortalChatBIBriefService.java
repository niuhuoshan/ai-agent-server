package group.aitools.nhs.platform.nhs.portal.chatbi;

import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.data.domain.AgentDataQuery;
import group.aitools.nhs.platform.nhs.service.GeneratedFileService;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 负责门户对话BIBrief相关的业务编排与领域规则处理。
 * Creates a deterministic, traceable brief from the supplied ChatBI report. */
@Service
public class PortalChatBIBriefService {

    private final CurrentPrincipalProvider principalProvider;
    private final AgentChatBIBriefMapper mapper;
    private final PortalChatBIQueryMapper queryMapper;
    private final PortalChatBIModelGateway modelGateway;
    private final GeneratedFileService generatedFileService;
    private final JsonMapper jsonMapper;

    public PortalChatBIBriefService(
        CurrentPrincipalProvider principalProvider,
        AgentChatBIBriefMapper mapper,
        PortalChatBIQueryMapper queryMapper,
        PortalChatBIModelGateway modelGateway,
        GeneratedFileService generatedFileService,
        JsonMapper jsonMapper
    ) {
        this.principalProvider = principalProvider;
        this.mapper = mapper;
        this.queryMapper = queryMapper;
        this.modelGateway = modelGateway;
        this.generatedFileService = generatedFileService;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> create(CreateBriefRequest request) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        if (!principal.isHuman()) {
            throw new ServiceException("机器主体不能创建门户业务简报", HttpStatus.FORBIDDEN);
        }
        Long queryId = queryId(request.resultId());
        AgentDataQuery query = queryMapper.selectOwnedQuery(queryId, principal.id());
        if (query == null || !"succeeded".equals(query.getStatus())) {
            throw new ServiceException("可生成简报的 ChatBI 查询结果不存在", HttpStatus.NOT_FOUND);
        }
        String conversationId = String.valueOf(query.getConversationId());
        String assistantReport = queryMapper.selectOwnedAssistantAnalysis(queryId, principal.id());
        assistantReport = assistantReport == null ? "" : assistantReport.strip();
        if (assistantReport.isBlank()) {
            throw new ServiceException("ChatBI 查询尚未生成可复用的分析正文", HttpStatus.CONFLICT);
        }
        if (request.polishWithLlm()) {
            assistantReport = polish(assistantReport, query);
        }
        String title = request.title() == null || request.title().isBlank()
            ? deriveTitle(assistantReport) : text(request.title(), 255, "简报标题");
        String resultId = String.valueOf(queryId);
        String markdown = markdown(title, assistantReport, resultId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("conversation_id", conversationId);
        payload.put("result_id", resultId);
        payload.put("source", "chatbi");
        payload.put("polish_with_llm", request.polishWithLlm());
        payload.put("source_query_id", queryId);

        Map<String, Object> artifact = null;
        if (request.exportWord()) {
            artifact = publishWord(markdown, title);
        }
        AgentChatBIBrief row = new AgentChatBIBrief();
        row.setId("brief_" + UUID.randomUUID().toString().replace("-", ""));
        row.setOwnerId(principal.id());
        row.setConversationId(conversationId);
        row.setResultId(resultId);
        row.setTitle(title);
        row.setBriefPayload(jsonMapper.writeValueAsString(payload));
        row.setMarkdownContent(markdown);
        row.setArtifactPayload(artifact == null ? null : jsonMapper.writeValueAsString(artifact));
        row.setCreatedAt(LocalDateTime.now());
        row.setDelFlag("0");
        mapper.insert(row);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.getId());
        result.put("title", title);
        result.put("markdown", markdown);
        result.put("artifact", artifact);
        result.put("conversation_id", conversationId);
        result.put("result_id", resultId);
        return result;
    }

    /**
     * 处理{@code polish}并返回对应结果。
     *
     * @param report 报表参数
     * @param query 查询参数
     * @return 处理结果
     */
    private String polish(String report, AgentDataQuery query) {
        String prompt = """
            你是企业 ChatBI 简报编辑。只允许调整结构、标题和措辞，不得增加、删除或改变任何事实、
            数值、时间范围、数据口径和结论。只输出润色后的 Markdown 正文，不要代码围栏。
            来源问题：%s
            来源 SQL：%s
            原始分析：
            %s
            """.formatted(query.getUserQuery(), query.getSqlText(), report);
        String polished = modelGateway.complete(
            "严格保真地润色 ChatBI 分析，不得编造任何数据。", prompt
        ).content().strip();
        if (polished.isBlank() || polished.length() > 200_000) {
            throw new ServiceException("模型没有返回有效的简报正文", 502);
        }
        return polished;
    }

    /**
     * 获取{@code Id}。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Long queryId(String value) {
        String normalized = text(value, 128, "结果标识");
        try {
            long id = Long.parseLong(normalized);
            if (id <= 0) {
                throw new NumberFormatException();
            }
            return id;
        } catch (NumberFormatException exception) {
            throw new ServiceException("结果标识必须是有效的 ChatBI 查询 ID", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 处理{@code publishWord}并返回对应结果。
     *
     * @param markdown {@code markdown}参数
     * @param title {@code title}参数
     * @return 处理结果
     */
    private Map<String, Object> publishWord(String markdown, String title) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        Path source = null;
        try {
            source = Files.createTempFile("agent-chatbi-brief-", ".docx");
            try (XWPFDocument document = new XWPFDocument();
                 OutputStream output = Files.newOutputStream(source)) {
                for (String line : markdown.split("\\R", -1)) {
                    XWPFParagraph paragraph = document.createParagraph();
                    String value = line.strip();
                    boolean heading = value.startsWith("#");
                    if (heading) {
                        value = value.replaceFirst("^#+\\s*", "");
                    }
                    XWPFRun run = paragraph.createRun();
                    run.setText(value);
                    if (heading) {
                        run.setBold(true);
                        run.setFontSize(value.startsWith(">") ? 12 : 16);
                    }
                }
                document.write(output);
            }
            GeneratedFileService.PublishedFile published = generatedFileService.publish(
                source, safeFileName(title) + ".docx"
            );
            return Map.of(
                "artifact_id", published.artifactId(),
                "filename", published.fileName(),
                "mime_type", published.mimeType(),
                "size", published.size(),
                "expires_at", published.expiresAt().toString(),
                "download_url", "/api/v1/chat/generated-files/" + published.artifactId()
                    + "?token=" + published.token(),
                "format", "docx"
            );
        } catch (IOException exception) {
            throw new ServiceException("简报文件生成失败", HttpStatus.ERROR);
        } finally {
            if (source != null) {
                try {
                    Files.deleteIfExists(source);
                } catch (IOException ignored) {
                    // Temporary-file cleanup is best effort after publication.
                }
            }
        }
    }

    /**
     * 处理{@code markdown}并返回对应结果。
     *
     * @param title {@code title}参数
     * @param report 报表参数
     * @param resultId 资源标识
     * @return 处理结果
     */
    private String markdown(String title, String report, String resultId) {
        StringBuilder value = new StringBuilder("# ").append(title).append("\n\n");
        value.append("> 来源：ChatBI");
        if (resultId != null) {
            value.append("；结果：").append(resultId);
        }
        value.append("\n\n").append(report).append("\n");
        return value.toString();
    }

    /**
     * 处理{@code deriveTitle}并返回对应结果。
     *
     * @param report 报表参数
     * @return 处理结果
     */
    private String deriveTitle(String report) {
        String first = report.lines().map(String::strip).filter(value -> !value.isBlank()).findFirst()
            .orElse("ChatBI 业务简报");
        if (first.startsWith("#")) {
            first = first.replaceFirst("^#+\\s*", "");
        }
        return first.length() > 255 ? first.substring(0, 255) : first;
    }

    /**
     * 处理safe文件Name并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String safeFileName(String value) {
        String normalized = value.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").strip();
        return normalized.isBlank() ? "ChatBI业务简报" : normalized;
    }

    /**
     * 处理{@code text}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param max {@code max}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String text(String value, int max, String label) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank() || normalized.length() > max || normalized.indexOf('\0') >= 0) {
            throw new ServiceException(label + "为空或超过长度限制", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    /**
     * 封装{@code CreateBrief}相关的不可变数据。
     */
    public record CreateBriefRequest(
        String resultId,
        boolean exportWord,
        boolean polishWithLlm,
        String title
    ) {
    }
}
