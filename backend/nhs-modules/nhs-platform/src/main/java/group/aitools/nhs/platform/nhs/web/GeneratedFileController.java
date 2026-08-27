package group.aitools.nhs.platform.nhs.web;

import cn.dev33.satoken.annotation.SaIgnore;
import group.aitools.nhs.platform.nhs.service.GeneratedFileService;
import group.aitools.nhs.platform.nhs.service.GeneratedFileService.GeneratedFile;
import group.aitools.nhs.platform.nhs.service.NhsV1OperationAuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;

/**
 * 提供Generated文件相关的 HTTP 接口，并负责请求校验与结果返回。
 * Public capability-link download endpoint. Possession of the unguessable token is the authority. */
@SaIgnore
@RestController
@RequestMapping("/api/v1/chat/generated-files")
public class GeneratedFileController {

    private final GeneratedFileService generatedFileService;
    private final NhsV1OperationAuditService auditService;

    @Autowired
    public GeneratedFileController(
        GeneratedFileService generatedFileService,
        NhsV1OperationAuditService auditService
    ) {
        this.generatedFileService = generatedFileService;
        this.auditService = auditService;
    }

    /**
     * 处理{@code download}并返回对应结果。
     *
     * @param artifactId 资源标识
     * @param token 令牌参数
     * @return 处理结果
     */
    @GetMapping("/{artifactId}")
    public ResponseEntity<Resource> download(
        @PathVariable String artifactId,
        @RequestParam String token
    ) {
        GeneratedFile file = generatedFileService.resolve(artifactId, token).orElse(null);
        if (file == null) {
            ResponseStatusException denial = new ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "文件不存在或已过期"
            );
            auditFailure("deny", "capability_invalid_or_expired", artifactId, null, denial);
            throw denial;
        }
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(file.mimeType());
        } catch (IllegalArgumentException exception) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        ContentDisposition disposition = ContentDisposition.attachment()
            .filename(file.fileName(), StandardCharsets.UTF_8)
            .build();
        audit("success", "capability_accepted", artifactId, file);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .header("X-Content-Type-Options", "nosniff")
            .cacheControl(CacheControl.noStore())
            .contentType(mediaType)
            .contentLength(file.size())
            .body(new FileSystemResource(file.path()));
    }

    /**
     * 处理审计相关逻辑。
     *
     * @param decision {@code decision}参数
     * @param reason {@code reason}参数
     * @param artifactId 资源标识
     * @param file 文件参数
     */
    private void audit(
        String decision,
        String reason,
        String artifactId,
        GeneratedFile file
    ) {
        String summary = "artifactFingerprint=" + auditService.fingerprint(artifactId)
            + (file == null ? "" : "; size=" + file.size() + "; mime=" + file.mimeType());
        auditService.recordApplication(
            "generated_file.download", "generated_file", null,
            decision, reason, summary
        );
    }

    /**
     * 处理审计Failure相关逻辑。
     *
     * @param decision {@code decision}参数
     * @param reason {@code reason}参数
     * @param artifactId 资源标识
     * @param file 文件参数
     * @param operationFailure 操作Failure参数
     */
    private void auditFailure(
        String decision,
        String reason,
        String artifactId,
        GeneratedFile file,
        RuntimeException operationFailure
    ) {
        try {
            audit(decision, reason, artifactId, file);
        } catch (RuntimeException auditFailure) {
            if (auditFailure != operationFailure) {
                auditFailure.addSuppressed(operationFailure);
            }
            throw auditFailure;
        }
    }
}
