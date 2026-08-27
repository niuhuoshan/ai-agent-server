package group.aitools.nhs.platform.conversation.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import group.aitools.nhs.platform.conversation.service.ConversationApplicationService;
import group.aitools.nhs.platform.conversation.service.ConversationAttachmentService;
import group.aitools.nhs.platform.conversation.service.ConversationAttachmentService.AttachmentDownload;
import group.aitools.nhs.platform.conversation.service.ConversationExportService;
import group.aitools.nhs.platform.conversation.service.ConversationExportService.ConversationExport;
import group.aitools.nhs.platform.conversation.service.ConversationGovernanceService;
import group.aitools.nhs.platform.conversation.service.ConversationTurnApplicationService;
import group.aitools.nhs.platform.conversation.service.ConversationApplicationService.BranchStart;
import group.aitools.nhs.platform.task.web.ConvertConversationToTaskRequest;
import group.aitools.nhs.platform.task.web.TaskConversionResult;
import group.aitools.nhs.platform.task.web.TaskDraftView;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 提供平台会话相关的 HTTP 接口，并负责请求校验与结果返回。
 * Private conversation and explicit task-conversion endpoints. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/platform/conversations")
public class PlatformConversationController {

    private final ConversationApplicationService conversationService;
    private final ConversationTurnApplicationService turnService;
    private final ConversationAttachmentService attachmentService;
    private final ConversationGovernanceService governanceService;
    private final ConversationExportService exportService;

    public PlatformConversationController(
        ConversationApplicationService conversationService,
        ConversationTurnApplicationService turnService,
        ConversationAttachmentService attachmentService,
        ConversationGovernanceService governanceService,
        ConversationExportService exportService
    ) {
        this.conversationService = conversationService;
        this.turnService = turnService;
        this.attachmentService = attachmentService;
        this.governanceService = governanceService;
        this.exportService = exportService;
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping
    public R<ConversationView> create(@Valid @RequestBody CreateConversationRequest request) {
        return R.ok(conversationService.create(request));
    }

    /**
     * 查询{@code list}列表。
     *
     * @param limit 数量上限
     * @param search {@code search}参数
     * @return 处理结果
     */
    @GetMapping
    public R<List<ConversationView>> list(
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
        @RequestParam(required = false) @Size(max = 255) String search
    ) {
        return R.ok(conversationService.list(search, limit));
    }

    /**
     * 获取{@code get}。
     *
     * @param conversationId 资源标识
     * @return 处理结果
     */
    @GetMapping("/{conversationId}")
    public R<ConversationView> get(@PathVariable @Positive Long conversationId) {
        return R.ok(conversationService.get(conversationId));
    }

    /**
     * 删除{@code delete}。
     *
     * @param conversationId 资源标识
     * @return 处理结果
     */
    @DeleteMapping("/{conversationId}")
    public R<Void> delete(@PathVariable @Positive Long conversationId) {
        governanceService.deleteConversation(conversationId);
        return R.ok();
    }

    /**
     * 处理{@code messages}并返回对应结果。
     *
     * @param conversationId 资源标识
     * @param afterSequence 起始位置或序号
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/{conversationId}/messages")
    public R<List<ConversationMessageView>> messages(
        @PathVariable @Positive Long conversationId,
        @RequestParam(defaultValue = "0") @Min(0) int afterSequence,
        @RequestParam(defaultValue = "200") @Min(1) @Max(500) int limit
    ) {
        return R.ok(conversationService.messages(conversationId, afterSequence, limit));
    }

    /**
     * 处理start会话回合并返回对应结果。
     *
     * @param conversationId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/{conversationId}/messages")
    public R<ConversationTurnView> startTurn(
        @PathVariable @Positive Long conversationId,
        @Valid @RequestBody CreateConversationTurnRequest request
    ) {
        return R.ok(turnService.start(conversationId, request));
    }

    /**
     * 创建并保存{@code Branch}。
     *
     * @param conversationId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/{conversationId}/branches")
    public R<ConversationView> createBranch(
        @PathVariable @Positive Long conversationId,
        @Valid @RequestBody CreateConversationBranchRequest request
    ) {
        return R.ok(conversationService.createBranch(conversationId, request).conversation());
    }

    /**
     * 处理{@code regenerate}并返回对应结果。
     *
     * @param conversationId 资源标识
     * @param messageId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/{conversationId}/messages/{messageId}/regenerate")
    public R<ConversationBranchView> regenerate(
        @PathVariable @Positive Long conversationId,
        @PathVariable @Positive Long messageId,
        @Valid @RequestBody CreateConversationBranchRequest request
    ) {
        if (!messageId.equals(request.forkMessageId())) {
            throw new group.aitools.nhs.common.core.exception.ServiceException(
                "路径消息与分支消息不一致", group.aitools.nhs.common.core.constant.HttpStatus.BAD_REQUEST
            );
        }
        BranchStart branch = conversationService.createBranch(conversationId, request);
        ConversationTurnView turn = turnService.start(
            branch.conversation().id(),
            new CreateConversationTurnRequest(
                request.idempotencyKey(), branch.input(), null, null, List.of()
            )
        );
        return R.ok(new ConversationBranchView(
            branch.conversation(), turn, request.forkMessageId(),
            branch.conversation().contextCutoffSequence(), branch.replayed() || turn.replayed()
        ));
    }

    /**
     * 处理会话回合并返回对应结果。
     *
     * @param conversationId 资源标识
     * @param turnId 资源标识
     * @return 处理结果
     */
    @GetMapping("/{conversationId}/turns/{turnId}")
    public R<ConversationTurnView> turn(
        @PathVariable @Positive Long conversationId,
        @PathVariable @Positive Long turnId
    ) {
        return R.ok(turnService.get(conversationId, turnId));
    }

    /**
     * 处理active会话回合并返回对应结果。
     *
     * @param conversationId 资源标识
     * @return 处理结果
     */
    @GetMapping("/{conversationId}/turns/active")
    public R<ConversationTurnView> activeTurn(
        @PathVariable @Positive Long conversationId
    ) {
        return R.ok(turnService.active(conversationId));
    }

    /**
     * 处理stop会话回合并返回对应结果。
     *
     * @param conversationId 资源标识
     * @param turnId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/{conversationId}/turns/{turnId}/stop")
    public R<ConversationTurnView> stopTurn(
        @PathVariable @Positive Long conversationId,
        @PathVariable @Positive Long turnId,
        @Valid @RequestBody(required = false) StopConversationTurnRequest request
    ) {
        return R.ok(turnService.stop(
            conversationId, turnId, request == null ? null : request.reason()
        ));
    }

    /**
     * 处理retry会话回合并返回对应结果。
     *
     * @param conversationId 资源标识
     * @param traceId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/{conversationId}/traces/{traceId}/retry")
    public R<ConversationTurnView> retryTurn(
        @PathVariable @Positive Long conversationId,
        @PathVariable String traceId,
        @Valid @RequestBody RetryConversationTurnRequest request
    ) {
        return R.ok(turnService.retry(conversationId, traceId, request));
    }

    /**
     * 处理upload附件并返回对应结果。
     *
     * @param conversationId 资源标识
     * @param file 文件参数
     * @return 处理结果
     */
    @PostMapping(
        value = "/{conversationId}/attachments",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public R<ConversationAttachmentView> uploadAttachment(
        @PathVariable @Positive Long conversationId,
        @RequestPart("file") MultipartFile file
    ) {
        return R.ok(attachmentService.upload(conversationId, file));
    }

    /**
     * 处理{@code attachments}并返回对应结果。
     *
     * @param conversationId 资源标识
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/{conversationId}/attachments")
    public R<List<ConversationAttachmentView>> attachments(
        @PathVariable @Positive Long conversationId,
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit
    ) {
        return R.ok(attachmentService.list(conversationId, limit));
    }

    /**
     * 处理反馈并返回对应结果。
     *
     * @param conversationId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/{conversationId}/feedback")
    public R<ConversationFeedbackView> feedback(
        @PathVariable @Positive Long conversationId,
        @Valid @RequestBody ConversationFeedbackRequest request
    ) {
        return R.ok(governanceService.saveFeedback(conversationId, request));
    }

    /**
     * 处理资源范围并返回对应结果。
     *
     * @param conversationId 资源标识
     * @return 处理结果
     */
    @GetMapping("/{conversationId}/resource-scope")
    public R<ConversationResourceScopeView> resourceScope(
        @PathVariable @Positive Long conversationId
    ) {
        return R.ok(governanceService.resourceScope(conversationId));
    }

    /**
     * 更新资源范围。
     *
     * @param conversationId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PutMapping("/{conversationId}/resource-scope")
    public R<ConversationResourceScopeView> updateResourceScope(
        @PathVariable @Positive Long conversationId,
        @Valid @RequestBody ConversationResourceScopeRequest request
    ) {
        return R.ok(governanceService.updateResourceScope(conversationId, request));
    }

    /**
     * 处理导出并返回对应结果。
     *
     * @param conversationId 资源标识
     * @param format {@code format}参数
     * @return 处理结果
     */
    @GetMapping("/{conversationId}/export")
    public ResponseEntity<byte[]> export(
        @PathVariable @Positive Long conversationId,
        @RequestParam(defaultValue = "markdown") String format
    ) {
        ConversationExport exported = exportService.export(conversationId, format);
        ContentDisposition disposition = ContentDisposition.attachment()
            .filename(exported.fileName(), StandardCharsets.UTF_8)
            .build();
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .contentType(MediaType.parseMediaType(exported.mediaType()))
            .contentLength(exported.content().length)
            .body(exported.content());
    }

    /**
     * 处理download附件并返回对应结果。
     *
     * @param conversationId 资源标识
     * @param attachmentId 资源标识
     * @return 处理结果
     */
    @GetMapping("/{conversationId}/attachments/{attachmentId}/content")
    public ResponseEntity<InputStreamResource> downloadAttachment(
        @PathVariable @Positive Long conversationId,
        @PathVariable @Positive Long attachmentId
    ) {
        AttachmentDownload download = attachmentService.download(conversationId, attachmentId);
        ContentDisposition disposition = ContentDisposition.attachment()
            .filename(download.attachment().getOriginalName(), StandardCharsets.UTF_8)
            .build();
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .contentType(MediaType.parseMediaType(download.attachment().getMimeType()))
            .contentLength(download.attachment().getSizeBytes())
            .body(new InputStreamResource(download.input()));
    }

    /**
     * 将输入数据转换为任务。
     *
     * @param conversationId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/{conversationId}/convert-task")
    public R<TaskConversionResult> convertTask(
        @PathVariable @Positive Long conversationId,
        @Valid @RequestBody ConvertConversationToTaskRequest request
    ) {
        return R.ok(conversationService.convertToTask(conversationId, request));
    }

    /**
     * 处理preview任务Draft并返回对应结果。
     *
     * @param conversationId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/{conversationId}/task-draft")
    public R<TaskDraftView> previewTaskDraft(
        @PathVariable @Positive Long conversationId,
        @Valid @RequestBody ConvertConversationToTaskRequest request
    ) {
        return R.ok(conversationService.previewTaskDraft(conversationId, request));
    }
}
