package group.aitools.nhs.system.controller.system;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import group.aitools.nhs.common.core.domain.PageResult;
import group.aitools.nhs.common.core.domain.R;
import group.aitools.nhs.common.core.validate.AddGroup;
import group.aitools.nhs.common.core.validate.EditGroup;
import group.aitools.nhs.common.core.validate.QueryGroup;
import group.aitools.nhs.common.log.annotation.Log;
import group.aitools.nhs.common.log.enums.BusinessType;
import group.aitools.nhs.common.mybatis.core.page.PageQuery;
import group.aitools.nhs.common.redis.annotation.RepeatSubmit;
import group.aitools.nhs.common.web.core.BaseController;
import group.aitools.nhs.system.domain.bo.SysOssConfigBo;
import group.aitools.nhs.system.domain.vo.SysOssConfigVo;
import group.aitools.nhs.system.service.ISysOssConfigService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 对象存储配置
 *
 * @author Lion Li
 * @author 孤舟烟雨
 * @date 2021-08-13
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/resource/oss/config")
public class SysOssConfigController extends BaseController {

    private final ISysOssConfigService ossConfigService;

    /**
     * 分页查询对象存储配置列表。
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 配置分页数据
     */
    @SaCheckPermission("system:ossConfig:list")
    @GetMapping("/list")
    public R<PageResult<SysOssConfigVo>> list(@Validated(QueryGroup.class) SysOssConfigBo bo, PageQuery pageQuery) {
        return R.ok(ossConfigService.queryPageList(bo, pageQuery));
    }

    /**
     * 获取单个对象存储配置详情。
     *
     * @param ossConfigId OSS配置ID
     * @return 配置详情
     */
    @SaCheckPermission("system:ossConfig:list")
    @GetMapping("/{ossConfigId}")
    public R<SysOssConfigVo> getInfo(@NotNull(message = "主键不能为空")
                                     @PathVariable Long ossConfigId) {
        return R.ok(ossConfigService.queryById(ossConfigId));
    }

    /**
     * 新增对象存储配置。
     *
     * @param bo 配置信息
     * @return 操作结果
     */
    @SaCheckPermission("system:ossConfig:add")
    @Log(title = "对象存储配置", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping()
    public R<Void> add(@Validated(AddGroup.class) @RequestBody SysOssConfigBo bo) {
        return toAjax(ossConfigService.insertByBo(bo));
    }

    /**
     * 修改对象存储配置。
     *
     * @param bo 配置信息
     * @return 操作结果
     */
    @SaCheckPermission("system:ossConfig:edit")
    @Log(title = "对象存储配置", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping()
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody SysOssConfigBo bo) {
        return toAjax(ossConfigService.updateByBo(bo));
    }

    /**
     * 批量删除对象存储配置。
     *
     * @param ossConfigIds OSS配置ID串
     * @return 操作结果
     */
    @SaCheckPermission("system:ossConfig:remove")
    @Log(title = "对象存储配置", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ossConfigIds}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ossConfigIds) {
        return toAjax(ossConfigService.deleteWithValidByIds(List.of(ossConfigIds), true));
    }

    /**
     * 切换对象存储配置启用状态，并同步更新当前生效配置。
     *
     * @param bo 状态变更信息
     * @return 操作结果
     */
    @SaCheckPermission("system:ossConfig:edit")
    @Log(title = "对象存储状态修改", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping("/changeStatus")
    public R<Void> changeStatus(@RequestBody SysOssConfigBo bo) {
        return toAjax(ossConfigService.updateOssConfigStatus(bo));
    }
}
