package group.aitools.nhs.system.controller.system;

import lombok.RequiredArgsConstructor;
import group.aitools.nhs.common.core.domain.R;
import group.aitools.nhs.common.satoken.utils.LoginHelper;
import group.aitools.nhs.common.web.core.BaseController;
import group.aitools.nhs.system.domain.vo.SysMessageBoxVo;
import group.aitools.nhs.system.service.ISysMessageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 消息记录控制器
 *
 * @author Lion Li
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/resource/message")
public class SysMessageController extends BaseController {

    private final ISysMessageService messageService;

    /**
     * 查询当前用户消息盒子数据
     *
     * @return 消息盒子数据
     */
    @GetMapping("/box")
    public R<SysMessageBoxVo> getBox() {
        return R.ok(messageService.queryMessageBox(LoginHelper.getUserId()));
    }
}
