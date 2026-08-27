package group.aitools.nhs.platform.skill.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import group.aitools.nhs.platform.skill.service.SkillCatalogService;
import group.aitools.nhs.platform.skill.service.SkillFileBundleService;
import group.aitools.nhs.common.core.domain.R;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.http.MediaType;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.nio.charset.StandardCharsets;

/**
 * 提供NhsPersonal技能文件相关的 HTTP 接口，并负责请求校验与结果返回。
 * Nhs personal Skill file aliases backed by the versioned platform bundle. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping({"/api/portal/personal-skills", "/api/portal/skills/personal"})
public class NhsPersonalSkillFileController {

    private final SkillCatalogService catalogService;
    private final SkillFileBundleService fileService;

    public NhsPersonalSkillFileController(
        SkillCatalogService catalogService,
        SkillFileBundleService fileService
    ) {
        this.catalogService = catalogService;
        this.fileService = fileService;
    }

    /**
     * 处理文件并返回对应结果。
     *
     * @param skillId 资源标识
     * @param path {@code path}参数
     * @return 处理结果
     */
    @GetMapping("/{skillId}/files")
    public R<SkillFileView> file(
        @PathVariable @Positive Long skillId,
        @RequestParam @Size(max = 512) String path
    ) {
        return R.ok(fileService.get(skillId, catalogService.latestVersionId(skillId), path));
    }

    /**
     * 处理{@code put}并返回对应结果。
     *
     * @param skillId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PutMapping("/{skillId}/files")
    public R<SkillFileView> put(
        @PathVariable @Positive Long skillId,
        @Valid @RequestBody PutSkillFileRequest request
    ) {
        return R.ok(fileService.put(
            skillId, catalogService.latestEditableVersionId(skillId), request.path(), request.content()
        ));
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param skillId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/{skillId}/files")
    public R<SkillFileView> create(
        @PathVariable @Positive Long skillId,
        @Valid @RequestBody CreateSkillFileEntryRequest request
    ) {
        Long versionId = catalogService.latestEditableVersionId(skillId);
        return R.ok("directory".equals(request.kind())
            ? fileService.createDirectory(skillId, versionId, request.path())
            : fileService.put(skillId, versionId, request.path(), ""));
    }

    /**
     * 处理{@code upload}并返回对应结果。
     *
     * @param skillId 资源标识
     * @param folder {@code folder}参数
     * @param file 文件参数
     * @return 处理结果
     */
    @PostMapping(
        value = "/{skillId}/upload",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public R<SkillFileView> upload(
        @PathVariable @Positive Long skillId,
        @RequestParam(required = false) @Size(max = 512) String folder,
        @RequestPart("file") MultipartFile file
    ) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            throw new ServiceException("上传文件名不能为空", 400);
        }
        String path = folder == null || folder.isBlank() ? name : folder + "/" + name;
        try {
            return R.ok(fileService.putBytes(
                skillId, catalogService.latestEditableVersionId(skillId), path, file.getBytes()
            ));
        } catch (IOException exception) {
            throw new ServiceException("技能文件读取失败", 400);
        }
    }

    /**
     * 处理{@code uploadArchive}并返回对应结果。
     *
     * @param skillId 资源标识
     * @param folder {@code folder}参数
     * @param file 文件参数
     * @return 处理结果
     */
    @PostMapping(
        value = "/{skillId}/upload-archive",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public R<List<SkillFileView>> uploadArchive(
        @PathVariable @Positive Long skillId,
        @RequestParam(required = false) @Size(max = 512) String folder,
        @RequestPart("file") MultipartFile file
    ) {
        try {
            return R.ok(fileService.importArchive(
                skillId, catalogService.latestEditableVersionId(skillId), file.getBytes(), folder,
                file.getOriginalFilename()
            ));
        } catch (IOException exception) {
            throw new ServiceException("技能压缩包读取失败", 400);
        }
    }

    /**
     * 处理{@code download}并返回对应结果。
     *
     * @param skillId 资源标识
     * @return 处理结果
     */
    @GetMapping(value = "/{skillId}/download", produces = "application/zip")
    public ResponseEntity<byte[]> download(@PathVariable @Positive Long skillId) {
        Long versionId = catalogService.latestVersionId(skillId);
        byte[] archive = fileService.exportArchive(skillId, versionId);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/zip"))
            .contentLength(archive.length)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename("skill-" + skillId + "-version-" + versionId + ".zip", StandardCharsets.UTF_8)
                .build().toString())
            .header("X-Content-Type-Options", "nosniff")
            .body(archive);
    }

    /**
     * 删除{@code delete}。
     *
     * @param skillId 资源标识
     * @param path {@code path}参数
     * @return 处理结果
     */
    @DeleteMapping("/{skillId}/files")
    public R<Void> delete(
        @PathVariable @Positive Long skillId,
        @RequestParam @Size(max = 512) String path
    ) {
        fileService.delete(skillId, catalogService.latestEditableVersionId(skillId), path);
        return R.ok();
    }
}
