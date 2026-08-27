package group.aitools.nhs.platform.knowledge.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import group.aitools.nhs.platform.knowledge.service.KnowledgeApplicationService;
import group.aitools.nhs.platform.knowledge.service.KnowledgeApplicationService.DocumentDownload;
import group.aitools.nhs.platform.knowledge.service.KnowledgeMetricsService;
import group.aitools.nhs.platform.knowledge.service.KnowledgeRetrievalService;
import group.aitools.nhs.platform.knowledge.service.KnowledgeDirectoryAccessService;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.provider.ExternalKnowledgeProviderRegistry;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.core.io.InputStreamResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * 提供平台知识库相关的 HTTP 接口，并负责请求校验与结果返回。
 */
@Validated
@SaCheckLogin
@RestController
@RequestMapping({"/platform/knowledge-bases", "/api/portal/knowledge-bases"})
public class PlatformKnowledgeController {

    private final KnowledgeApplicationService applicationService;
    private final KnowledgeRetrievalService retrievalService;
    private final KnowledgeMetricsService metricsService;
    private final ExternalKnowledgeProviderRegistry providerRegistry;
    private final KnowledgeDirectoryAccessService directoryAccess;
    private final CurrentPrincipalProvider principalProvider;

    /**
     * 创建 {@code PlatformKnowledgeController} 实例并初始化所需依赖。
     *
     * @param applicationService 应用Service参数
     * @param retrievalService {@code retrievalService}参数
     * @param metricsService {@code metricsService}参数
     * @param providerRegistry 提供方Registry参数
     */
    public PlatformKnowledgeController(
        KnowledgeApplicationService applicationService,
        KnowledgeRetrievalService retrievalService,
        KnowledgeMetricsService metricsService,
        ExternalKnowledgeProviderRegistry providerRegistry
    ) {
        this(applicationService, retrievalService, metricsService, providerRegistry, null, null);
    }

    /**
     * 创建 {@code PlatformKnowledgeController} 实例并初始化所需依赖。
     *
     * @param applicationService 应用Service参数
     * @param retrievalService {@code retrievalService}参数
     * @param metricsService {@code metricsService}参数
     * @param providerRegistry 提供方Registry参数
     * @param directoryAccess 目录Access参数
     * @param principalProvider 操作主体提供方参数
     */
    @Autowired
    public PlatformKnowledgeController(
        KnowledgeApplicationService applicationService,
        KnowledgeRetrievalService retrievalService,
        KnowledgeMetricsService metricsService,
        ExternalKnowledgeProviderRegistry providerRegistry,
        KnowledgeDirectoryAccessService directoryAccess,
        CurrentPrincipalProvider principalProvider
    ) {
        this.applicationService = applicationService;
        this.retrievalService = retrievalService;
        this.metricsService = metricsService;
        this.providerRegistry = providerRegistry;
        this.directoryAccess = directoryAccess;
        this.principalProvider = principalProvider;
    }

    /**
 * 处理{@code providers}并返回对应结果。
 * Returns provider readiness without exposing credentials or endpoints. */
    @GetMapping("/providers")
    public R<List<KnowledgeProviderStatusView>> providers() {
        return R.ok(providerRegistry.statuses().stream()
            .map(status -> new KnowledgeProviderStatusView(
                status.providerType(), status.available(), status.state(), status.message()
            ))
            .toList());
    }

    /**
     * 查询{@code list}列表。
     *
     * @param search {@code search}参数
     * @param includeInactive {@code includeInactive}参数
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping
    public R<List<KnowledgeBaseView>> list(
        @RequestParam(required = false) String search,
        @RequestParam(defaultValue = "false") boolean includeInactive,
        @RequestParam(defaultValue = "200") @Min(1) @Max(500) int limit
    ) {
        return R.ok(applicationService.list(search, includeInactive, limit));
    }

    /**
     * 获取{@code get}。
     *
     * @param baseId 资源标识
     * @return 处理结果
     */
    @GetMapping("/{baseId}")
    public R<KnowledgeBaseView> get(@PathVariable @Positive Long baseId) {
        return R.ok(applicationService.get(baseId));
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping
    public R<KnowledgeBaseView> create(@Valid @RequestBody CreateKnowledgeBaseRequest request) {
        return R.ok(applicationService.create(request));
    }

    /**
     * 更新{@code update}。
     *
     * @param baseId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PutMapping("/{baseId}")
    public R<KnowledgeBaseView> update(
        @PathVariable @Positive Long baseId,
        @Valid @RequestBody UpdateKnowledgeBaseRequest request
    ) {
        return R.ok(applicationService.update(baseId, request));
    }

    /**
     * 删除{@code delete}。
     *
     * @param baseId 资源标识
     * @param expectedRevision {@code expectedRevision}参数
     * @return 处理结果
     */
    @DeleteMapping("/{baseId}")
    public R<Void> delete(
        @PathVariable @Positive Long baseId,
        @RequestParam @Positive Long expectedRevision
    ) {
        applicationService.delete(baseId, expectedRevision);
        return R.ok();
    }

    /**
     * 处理{@code documents}并返回对应结果。
     *
     * @param baseId 资源标识
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/{baseId}/documents")
    public R<List<KnowledgeDocumentView>> documents(
        @PathVariable @Positive Long baseId,
        @RequestParam(defaultValue = "200") @Min(1) @Max(500) int limit
    ) {
        return R.ok(applicationService.documents(baseId, limit));
    }

    /**
     * 处理{@code tree}并返回对应结果。
     *
     * @param baseId 资源标识
     * @return 处理结果
     */
    @GetMapping("/{baseId}/tree")
    public R<KnowledgeTreeView> tree(@PathVariable @Positive Long baseId) {
        return R.ok(applicationService.tree(baseId));
    }

    /**
     * 处理{@code directories}并返回对应结果。
     *
     * @param baseId 资源标识
     * @return 处理结果
     */
    @GetMapping("/{baseId}/directories")
    public R<List<KnowledgeDirectoryView>> directories(@PathVariable @Positive Long baseId) {
        return R.ok(applicationService.directories(baseId));
    }

    /**
     * 处理目录Acls并返回对应结果。
     *
     * @param baseId 资源标识
     * @param directoryId 资源标识
     * @param root {@code root}参数
     * @return 处理结果
     */
    @GetMapping("/{baseId}/directory-acls")
    public R<List<KnowledgeDirectoryAclView>> directoryAcls(
        @PathVariable @Positive Long baseId,
        @RequestParam(required = false) @Positive Long directoryId,
        @RequestParam(defaultValue = "false") boolean root
    ) {
        requireDirectoryAccessService();
        return R.ok(directoryAccess.list(baseId, directoryId, root, principalProvider.currentPrincipal()));
    }

    /**
     * 处理put目录Acl并返回对应结果。
     *
     * @param baseId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PutMapping("/{baseId}/directory-acls")
    public R<KnowledgeDirectoryAclView> putDirectoryAcl(
        @PathVariable @Positive Long baseId,
        @Valid @RequestBody PutKnowledgeDirectoryAclRequest request
    ) {
        requireDirectoryAccessService();
        return R.ok(directoryAccess.put(baseId, request, principalProvider.currentPrincipal()));
    }

    /**
     * 处理revoke目录Acl并返回对应结果。
     *
     * @param baseId 资源标识
     * @param aclId 资源标识
     * @param expectedRevision {@code expectedRevision}参数
     * @return 处理结果
     */
    @DeleteMapping("/{baseId}/directory-acls/{aclId}")
    public R<Void> revokeDirectoryAcl(
        @PathVariable @Positive Long baseId,
        @PathVariable @Positive Long aclId,
        @RequestParam @Positive Long expectedRevision
    ) {
        requireDirectoryAccessService();
        directoryAccess.revoke(
            baseId, aclId, expectedRevision, principalProvider.currentPrincipal()
        );
        return R.ok();
    }

    /**
     * 创建并保存目录。
     *
     * @param baseId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/{baseId}/directories")
    public R<KnowledgeDirectoryView> createDirectory(
        @PathVariable @Positive Long baseId,
        @Valid @RequestBody CreateKnowledgeDirectoryRequest request
    ) {
        return R.ok(applicationService.createDirectory(baseId, request));
    }

    /**
     * 处理patch目录并返回对应结果。
     *
     * @param baseId 资源标识
     * @param directoryId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PatchMapping("/{baseId}/directories/{directoryId}")
    public R<KnowledgeDirectoryView> patchDirectory(
        @PathVariable @Positive Long baseId,
        @PathVariable @Positive Long directoryId,
        @Valid @RequestBody UpdateKnowledgeDirectoryRequest request
    ) {
        return R.ok(applicationService.updateDirectory(baseId, directoryId, request));
    }

    /**
     * 处理put目录并返回对应结果。
     *
     * @param baseId 资源标识
     * @param directoryId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PutMapping("/{baseId}/directories/{directoryId}")
    public R<KnowledgeDirectoryView> putDirectory(
        @PathVariable @Positive Long baseId,
        @PathVariable @Positive Long directoryId,
        @Valid @RequestBody UpdateKnowledgeDirectoryRequest request
    ) {
        return patchDirectory(baseId, directoryId, request);
    }

    /**
     * 删除目录。
     *
     * @param baseId 资源标识
     * @param directoryId 资源标识
     * @param expectedRevision {@code expectedRevision}参数
     * @return 处理结果
     */
    @DeleteMapping("/{baseId}/directories/{directoryId}")
    public R<Void> deleteDirectory(
        @PathVariable @Positive Long baseId,
        @PathVariable @Positive Long directoryId,
        @RequestParam @Positive Long expectedRevision
    ) {
        applicationService.deleteDirectory(baseId, directoryId, expectedRevision);
        return R.ok();
    }

    /**
     * 处理{@code chunks}并返回对应结果。
     *
     * @param baseId 资源标识
     * @param documentId 资源标识
     * @param offset 起始位置或序号
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/{baseId}/documents/{documentId}/chunks")
    public R<List<KnowledgeChunkView>> chunks(
        @PathVariable @Positive Long baseId,
        @PathVariable @Positive Long documentId,
        @RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int offset,
        @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit
    ) {
        return R.ok(applicationService.chunks(baseId, documentId, offset, limit));
    }

    /**
     * 处理{@code download}并返回对应结果。
     *
     * @param baseId 资源标识
     * @param documentId 资源标识
     * @param inline {@code inline}参数
     * @return 处理结果
     */
    @GetMapping({
        "/{baseId}/documents/{documentId}/file",
        "/{baseId}/documents/{documentId}/content"
    })
    public ResponseEntity<InputStreamResource> download(
        @PathVariable @Positive Long baseId,
        @PathVariable @Positive Long documentId,
        @RequestParam(defaultValue = "false") boolean inline
    ) {
        DocumentDownload download = applicationService.download(baseId, documentId);
        MediaType mediaType = mediaType(download.document().getMimeType());
        ContentDisposition disposition = (inline && safeInline(mediaType)
            ? ContentDisposition.inline() : ContentDisposition.attachment())
            .filename(download.document().getName(), StandardCharsets.UTF_8)
            .build();
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .header("X-Content-Type-Options", "nosniff")
            .header("Content-Security-Policy", "sandbox; default-src 'none'")
            .cacheControl(CacheControl.noStore())
            .contentType(mediaType);
        Long sizeBytes = download.document().getSizeBytes();
        if (sizeBytes != null && sizeBytes >= 0) {
            response.contentLength(sizeBytes);
        }
        return response.body(new InputStreamResource(download.input()));
    }

    /**
     * 处理{@code upload}并返回对应结果。
     *
     * @param baseId 资源标识
     * @param file 文件参数
     * @param directoryId 资源标识
     * @return 处理结果
     */
    @PostMapping(value = "/{baseId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<KnowledgeDocumentView> upload(
        @PathVariable @Positive Long baseId,
        @RequestPart("file") MultipartFile file,
        @RequestParam(name = "directoryId", required = false) @Positive Long directoryId
    ) {
        return R.ok(applicationService.upload(baseId, file, directoryId));
    }

    /**
 * 处理{@code upload}并返回对应结果。
 * Source-compatible overload for callers that always upload to the root. */
    public R<KnowledgeDocumentView> upload(Long baseId, MultipartFile file) {
        return upload(baseId, file, null);
    }

    /**
     * 处理patch文档并返回对应结果。
     *
     * @param baseId 资源标识
     * @param documentId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PatchMapping("/{baseId}/documents/{documentId}")
    public R<KnowledgeDocumentView> patchDocument(
        @PathVariable @Positive Long baseId,
        @PathVariable @Positive Long documentId,
        @Valid @RequestBody UpdateKnowledgeDocumentRequest request
    ) {
        return R.ok(applicationService.updateDocument(baseId, documentId, request));
    }

    /**
     * 处理put文档并返回对应结果。
     *
     * @param baseId 资源标识
     * @param documentId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PutMapping("/{baseId}/documents/{documentId}")
    public R<KnowledgeDocumentView> putDocument(
        @PathVariable @Positive Long baseId,
        @PathVariable @Positive Long documentId,
        @Valid @RequestBody UpdateKnowledgeDocumentRequest request
    ) {
        return patchDocument(baseId, documentId, request);
    }

    /**
     * 处理{@code parse}并返回对应结果。
     *
     * @param baseId 资源标识
     * @param documentId 资源标识
     * @return 处理结果
     */
    @PostMapping("/{baseId}/documents/{documentId}/parse")
    public R<KnowledgeParseJobView> parse(
        @PathVariable @Positive Long baseId,
        @PathVariable @Positive Long documentId
    ) {
        return R.ok(applicationService.queueParse(baseId, documentId));
    }

    /**
     * 删除文档。
     *
     * @param baseId 资源标识
     * @param documentId 资源标识
     * @return 处理结果
     */
    @DeleteMapping("/{baseId}/documents/{documentId}")
    public R<Void> deleteDocument(
        @PathVariable @Positive Long baseId,
        @PathVariable @Positive Long documentId
    ) {
        applicationService.deleteDocument(baseId, documentId);
        return R.ok();
    }

    /**
     * 处理{@code retrieve}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/retrieve")
    public R<KnowledgeRetrievalView> retrieve(
        @Valid @RequestBody KnowledgeRetrieveRequest request
    ) {
        return R.ok(retrievalService.retrieve(request));
    }

    /**
     * 处理{@code metrics}并返回对应结果。
     *
     * @param days {@code days}参数
     * @param startDate {@code startDate}参数
     * @param endDate {@code endDate}参数
     * @return 处理结果
     */
    @GetMapping({"/metrics", "/metrics/summary"})
    public R<KnowledgeMetricsView> metrics(
        @RequestParam(defaultValue = "7") @Min(1) @Max(90) int days,
        @RequestParam(required = false, name = "start_date") String startDate,
        @RequestParam(required = false, name = "end_date") String endDate
    ) {
        return R.ok(metricsService.summary(days, startDate, endDate));
    }

    /**
     * 处理{@code mediaType}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private MediaType mediaType(String value) {
        if (value == null || value.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(value);
        } catch (IllegalArgumentException ignored) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    /**
     * 处理{@code safeInline}并返回对应结果。
     *
     * @param mediaType 业务类型
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean safeInline(MediaType mediaType) {
        return Set.of(
            "application/pdf", "application/json", "text/plain", "text/markdown", "text/csv"
        ).contains(mediaType.getType() + "/" + mediaType.getSubtype());
    }

    /**
     * 校验目录AccessService，并在条件不满足时终止处理。
     */
    private void requireDirectoryAccessService() {
        if (directoryAccess == null || principalProvider == null) {
            throw new group.aitools.nhs.common.core.exception.ServiceException(
                "知识目录 ACL 服务未启用", group.aitools.nhs.common.core.constant.HttpStatus.NOT_IMPLEMENTED
            );
        }
    }
}
