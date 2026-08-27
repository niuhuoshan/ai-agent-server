package group.aitools.nhs.platform.skill.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import group.aitools.nhs.platform.skill.service.SkillCatalogService;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import group.aitools.nhs.platform.skill.service.SkillFileBundleService;
import group.aitools.nhs.platform.skill.service.SkillDependencyInstallService;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.nio.charset.StandardCharsets;

/**
 * 提供平台技能相关的 HTTP 接口，并负责请求校验与结果返回。
 * Personal, project and system Skill lifecycle endpoints. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping({"/platform/skills", "/api/portal/skills"})
public class PlatformSkillController {

    private final SkillCatalogService service;
    private final SkillFileBundleService fileService;
    private SkillDependencyInstallService dependencyService;

    public PlatformSkillController(SkillCatalogService service, SkillFileBundleService fileService) {
        this.service = service;
        this.fileService = fileService;
    }

    /**
 * 设置{@code DependencyService}。
 * Optional setter keeps focused controller tests and older embedders source-compatible. */
    @Autowired(required = false)
    public void setDependencyService(SkillDependencyInstallService dependencyService) {
        this.dependencyService = dependencyService;
    }

    /**
     * 查询{@code list}列表。
     *
     * @param scopeType 业务类型
     * @param scopeId 资源标识
     * @param search {@code search}参数
     * @param includeInactive {@code includeInactive}参数
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping
    public R<List<SkillView>> list(
        @RequestParam(required = false) @Pattern(regexp = "system|project|user") String scopeType,
        @RequestParam(required = false) @Positive Long scopeId,
        @RequestParam(required = false) @Size(max = 128) String search,
        @RequestParam(defaultValue = "false") boolean includeInactive,
        @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit
    ) {
        return R.ok(service.list(scopeType, scopeId, search, includeInactive, limit));
    }

    /**
     * 处理{@code available}并返回对应结果。
     *
     * @param scopeType 业务类型
     * @param scopeId 资源标识
     * @param search {@code search}参数
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/available")
    public R<List<SkillView>> available(
        @RequestParam(required = false) @Pattern(regexp = "system|project|user") String scopeType,
        @RequestParam(required = false) @Positive Long scopeId,
        @RequestParam(required = false) @Size(max = 128) String search,
        @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit
    ) {
        return R.ok(service.available(scopeType, scopeId, search, limit));
    }

    /**
     * 获取{@code get}。
     *
     * @param skillId 资源标识
     * @return 处理结果
     */
    @GetMapping("/{skillId}")
    public R<SkillView> get(@PathVariable @Positive Long skillId) {
        return R.ok(service.get(skillId));
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping
    public R<SkillView> create(@Valid @RequestBody CreateSkillRequest request) {
        return R.ok(service.create(request));
    }

    /**
     * 更新{@code update}。
     *
     * @param skillId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PutMapping("/{skillId}")
    public R<SkillView> update(
        @PathVariable @Positive Long skillId,
        @Valid @RequestBody UpdateSkillRequest request
    ) {
        return R.ok(service.update(skillId, request));
    }

    /**
     * 更新{@code Status}。
     *
     * @param skillId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PatchMapping("/{skillId}/status")
    public R<SkillView> updateStatus(
        @PathVariable @Positive Long skillId,
        @Valid @RequestBody UpdateSkillStatusRequest request
    ) {
        return R.ok(service.updateStatus(skillId, request));
    }

    /**
     * 删除{@code delete}。
     *
     * @param skillId 资源标识
     * @param expectedRevision {@code expectedRevision}参数
     * @return 处理结果
     */
    @DeleteMapping("/{skillId}")
    public R<Void> delete(
        @PathVariable @Positive Long skillId,
        @RequestParam @Positive Long expectedRevision
    ) {
        service.delete(skillId, expectedRevision);
        return R.ok();
    }

    /**
     * 处理{@code versions}并返回对应结果。
     *
     * @param skillId 资源标识
     * @return 处理结果
     */
    @GetMapping("/{skillId}/versions")
    public R<List<SkillVersionView>> versions(@PathVariable @Positive Long skillId) {
        return R.ok(service.versions(skillId));
    }

    /**
     * 处理版本并返回对应结果。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @return 处理结果
     */
    @GetMapping("/{skillId}/versions/{versionId}")
    public R<SkillVersionView> version(
        @PathVariable @Positive Long skillId,
        @PathVariable @Positive Long versionId
    ) {
        return R.ok(service.version(skillId, versionId));
    }

    /**
     * 创建并保存版本。
     *
     * @param skillId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/{skillId}/versions")
    public R<SkillVersionView> createVersion(
        @PathVariable @Positive Long skillId,
        @Valid @RequestBody CreateSkillVersionRequest request
    ) {
        return R.ok(service.createVersion(skillId, request));
    }

    /**
     * 处理clone版本并返回对应结果。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/{skillId}/versions/{versionId}/clone")
    public R<SkillVersionView> cloneVersion(
        @PathVariable @Positive Long skillId,
        @PathVariable @Positive Long versionId,
        @Valid @RequestBody SkillLifecycleRequest request
    ) {
        return R.ok(service.cloneVersion(skillId, versionId, request.expectedRevision()));
    }

    /**
     * 删除版本。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @param expectedRevision {@code expectedRevision}参数
     * @return 处理结果
     */
    @DeleteMapping("/{skillId}/versions/{versionId}")
    public R<Void> deleteVersion(
        @PathVariable @Positive Long skillId,
        @PathVariable @Positive Long versionId,
        @RequestParam @Positive Long expectedRevision
    ) {
        service.deleteVersion(skillId, versionId, expectedRevision);
        return R.ok();
    }

    /**
     * 处理{@code publish}并返回对应结果。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/{skillId}/versions/{versionId}/publish")
    public R<SkillVersionView> publish(
        @PathVariable @Positive Long skillId,
        @PathVariable @Positive Long versionId,
        @Valid @RequestBody SkillLifecycleRequest request
    ) {
        return R.ok(service.publish(skillId, versionId, request.expectedRevision()));
    }

    /**
     * 处理archive版本并返回对应结果。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PatchMapping("/{skillId}/versions/{versionId}/archive")
    public R<SkillVersionView> archiveVersion(
        @PathVariable @Positive Long skillId,
        @PathVariable @Positive Long versionId,
        @Valid @RequestBody SkillLifecycleRequest request
    ) {
        return R.ok(service.archiveVersion(skillId, versionId, request.expectedRevision()));
    }

    /**
     * 处理{@code files}并返回对应结果。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @return 处理结果
     */
    @GetMapping("/{skillId}/versions/{versionId}/files")
    public R<List<SkillFileView>> files(
        @PathVariable @Positive Long skillId,
        @PathVariable @Positive Long versionId
    ) {
        return R.ok(fileService.list(skillId, versionId));
    }

    /**
     * 处理{@code dependencies}并返回对应结果。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @return 处理结果
     */
    @GetMapping("/{skillId}/versions/{versionId}/dependencies")
    public R<SkillDependencyInstallView> dependencies(
        @PathVariable @Positive Long skillId,
        @PathVariable @Positive Long versionId
    ) {
        return R.ok(requireDependencyService().inspect(skillId, versionId));
    }

    /**
     * 处理{@code installDependencies}并返回对应结果。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @return 处理结果
     */
    @PostMapping("/{skillId}/versions/{versionId}/dependencies/install")
    public R<SkillDependencyInstallView> installDependencies(
        @PathVariable @Positive Long skillId,
        @PathVariable @Positive Long versionId
    ) {
        return R.ok(requireDependencyService().install(skillId, versionId));
    }

    /**
     * 处理导出Archive并返回对应结果。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @return 处理结果
     */
    @GetMapping(value = "/{skillId}/versions/{versionId}/files/archive", produces = "application/zip")
    public ResponseEntity<byte[]> exportArchive(
        @PathVariable @Positive Long skillId,
        @PathVariable @Positive Long versionId
    ) {
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
     * 处理文件并返回对应结果。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @param path {@code path}参数
     * @return 处理结果
     */
    @GetMapping("/{skillId}/versions/{versionId}/files/content")
    public R<SkillFileView> file(
        @PathVariable @Positive Long skillId,
        @PathVariable @Positive Long versionId,
        @RequestParam @Size(max = 512) String path
    ) {
        return R.ok(fileService.get(skillId, versionId, path));
    }

    /**
     * 处理put文件并返回对应结果。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PutMapping("/{skillId}/versions/{versionId}/files")
    public R<SkillFileView> putFile(
        @PathVariable @Positive Long skillId,
        @PathVariable @Positive Long versionId,
        @Valid @RequestBody PutSkillFileRequest request
    ) {
        return R.ok(fileService.put(skillId, versionId, request.path(), request.content()));
    }

    /**
     * 创建并保存文件Entry。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/{skillId}/versions/{versionId}/files")
    public R<SkillFileView> createFileEntry(
        @PathVariable @Positive Long skillId,
        @PathVariable @Positive Long versionId,
        @Valid @RequestBody CreateSkillFileEntryRequest request
    ) {
        return R.ok("directory".equals(request.kind())
            ? fileService.createDirectory(skillId, versionId, request.path())
            : fileService.put(skillId, versionId, request.path(), ""));
    }

    /**
     * 处理upload文件并返回对应结果。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @param path {@code path}参数
     * @param file 文件参数
     * @return 处理结果
     */
    @PostMapping(
        value = "/{skillId}/versions/{versionId}/files/upload",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public R<SkillFileView> uploadFile(
        @PathVariable @Positive Long skillId,
        @PathVariable @Positive Long versionId,
        @RequestParam @Size(max = 512) String path,
        @RequestPart("file") MultipartFile file
    ) {
        try {
            return R.ok(fileService.putBytes(skillId, versionId, path, file.getBytes()));
        } catch (java.io.IOException exception) {
            throw new group.aitools.nhs.common.core.exception.ServiceException("技能文件读取失败", 400);
        }
    }

    /**
     * 处理{@code uploadArchive}并返回对应结果。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @param file 文件参数
     * @return 处理结果
     */
    @PostMapping(
        value = "/{skillId}/versions/{versionId}/files/upload-archive",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public R<List<SkillFileView>> uploadArchive(
        @PathVariable @Positive Long skillId,
        @PathVariable @Positive Long versionId,
        @RequestPart("file") MultipartFile file
    ) {
        try {
            return R.ok(fileService.importArchive(
                skillId, versionId, file.getBytes(), null, file.getOriginalFilename()
            ));
        } catch (java.io.IOException exception) {
            throw new group.aitools.nhs.common.core.exception.ServiceException("技能压缩包读取失败", 400);
        }
    }

    /**
     * 删除文件。
     *
     * @param skillId 资源标识
     * @param versionId 资源标识
     * @param path {@code path}参数
     * @return 处理结果
     */
    @DeleteMapping("/{skillId}/versions/{versionId}/files")
    public R<Void> deleteFile(
        @PathVariable @Positive Long skillId,
        @PathVariable @Positive Long versionId,
        @RequestParam @Size(max = 512) String path
    ) {
        fileService.delete(skillId, versionId, path);
        return R.ok();
    }

    /**
     * 校验{@code DependencyService}，并在条件不满足时终止处理。
     *
     * @return 处理结果
     */
    private SkillDependencyInstallService requireDependencyService() {
        if (dependencyService == null) {
            throw new group.aitools.nhs.common.core.exception.ServiceException(
                "Skill 依赖安装器当前未配置", 503
            );
        }
        return dependencyService;
    }
}
