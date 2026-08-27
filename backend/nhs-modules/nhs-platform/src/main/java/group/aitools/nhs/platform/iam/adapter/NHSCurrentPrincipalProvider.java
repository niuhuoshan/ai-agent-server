package group.aitools.nhs.platform.iam.adapter;

import lombok.RequiredArgsConstructor;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.common.satoken.utils.LoginHelper;
import group.aitools.nhs.system.api.model.LoginUser;
import org.springframework.stereotype.Component;

/**
 * 处理当前操作主体并返回对应结果。
 *
 * 负责NHS当前操作主体相关的转换、解析或处理逻辑。
 * Reads the current NHS session without consulting department or post data. */
@Component
@RequiredArgsConstructor
public final class NHSCurrentPrincipalProvider implements CurrentPrincipalProvider {

    @Override
    public CurrentPrincipal currentPrincipal() {
        LoginUser loginUser = LoginHelper.getLoginUser();
        if (loginUser == null) {
            throw new IllegalStateException("No authenticated platform principal is available");
        }
        return NHSPrincipalAdapter.adapt(loginUser);
    }
}
