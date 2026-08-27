package group.aitools.nhs.system.controller.monitor;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.lock.annotation.Lock4j;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import group.aitools.nhs.common.core.constant.CacheNames;
import group.aitools.nhs.common.core.domain.PageResult;
import group.aitools.nhs.common.core.domain.R;
import group.aitools.nhs.common.excel.utils.ExcelBuilder;
import group.aitools.nhs.common.log.annotation.Log;
import group.aitools.nhs.common.log.enums.BusinessType;
import group.aitools.nhs.common.mybatis.core.page.PageQuery;
import group.aitools.nhs.common.redis.annotation.RepeatSubmit;
import group.aitools.nhs.common.redis.utils.RedisUtils;
import group.aitools.nhs.common.web.core.BaseController;
import group.aitools.nhs.system.domain.bo.SysLoginInfoBo;
import group.aitools.nhs.system.domain.vo.SysLoginInfoVo;
import group.aitools.nhs.system.service.ISysLoginInfoService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 系统访问记录
 *
 * @author Lion Li
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/monitor/loginInfo")
public class SysLoginInfoController extends BaseController {

    private final ISysLoginInfoService loginInfoService;

    /**
     * 分页查询系统访问记录。
     *
     * @param loginInfo 查询条件
     * @param pageQuery 分页参数
     * @return 登录日志分页结果
     */
    @SaCheckPermission("monitor:logininfo:list")
    @GetMapping("/list")
    public R<PageResult<SysLoginInfoVo>> list(SysLoginInfoBo loginInfo, PageQuery pageQuery) {
        return R.ok(loginInfoService.selectPageLoginInfoList(loginInfo, pageQuery));
    }

    /**
     * 导出系统访问记录列表。
     *
     * @param loginInfo 查询条件
     * @param response  HTTP 响应
     */
    @Log(title = "登录日志", businessType = BusinessType.EXPORT)
    @SaCheckPermission("monitor:logininfo:export")
    @PostMapping("/export")
    public void export(SysLoginInfoBo loginInfo, HttpServletResponse response) {
        List<SysLoginInfoVo> list = loginInfoService.selectLoginInfoList(loginInfo);
        ExcelBuilder.of(list, SysLoginInfoVo.class).sheetName("登录日志").toResponse(response);
    }

    /**
     * 批量删除登录日志
     *
     * @param infoIds 日志ids
     * @return 操作结果
     */
    @SaCheckPermission("monitor:logininfo:remove")
    @Log(title = "登录日志", businessType = BusinessType.DELETE)
    @DeleteMapping("/{infoIds}")
    public R<Void> remove(@PathVariable Long[] infoIds) {
        return toAjax(loginInfoService.deleteLoginInfoByIds(infoIds));
    }

    /**
     * 清空系统访问记录。
     *
     * @return 操作结果
     */
    @SaCheckPermission("monitor:logininfo:remove")
    @Log(title = "登录日志", businessType = BusinessType.CLEAN)
    @Lock4j
    @DeleteMapping("/clean")
    public R<Void> clean() {
        loginInfoService.cleanLoginInfo();
        return R.ok();
    }

    /**
     * 清除指定用户的登录失败锁定状态。
     *
     * @param userName 用户名
     * @return 操作结果
     */
    @SaCheckPermission("monitor:logininfo:unlock")
    @Log(title = "账户解锁", businessType = BusinessType.OTHER)
    @RepeatSubmit()
    @GetMapping("/unlock/{userName}")
    public R<Void> unlock(@PathVariable("userName") String userName) {
        String loginName = CacheNames.PWD_ERR_CNT_KEY + userName;
        if (RedisUtils.hasKey(loginName)) {
            RedisUtils.deleteObject(loginName);
        }
        return R.ok();
    }

}
