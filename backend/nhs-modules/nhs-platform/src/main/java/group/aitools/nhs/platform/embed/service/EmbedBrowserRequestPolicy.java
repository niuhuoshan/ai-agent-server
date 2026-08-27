package group.aitools.nhs.platform.embed.service;

import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

/**
 * 校验{@code SameOrigin}，并在条件不满足时终止处理。
 *
 * 表示嵌入式会话浏览器Request策略相关的领域对象。
 * Rejects browser calls that bypass the same-origin widget iframe. */
@Service
public class EmbedBrowserRequestPolicy {

    public void requireSameOrigin(String fetchSite, String browserOrigin) {
        String site = normalized(fetchSite);
        String origin = normalized(browserOrigin);
        if ("same-origin".equals(site)) {
            return;
        }
        if (site == null && origin == null) {
            // Non-browser clients do not send Fetch Metadata or Origin headers.
            return;
        }
        throw new ServiceException("Embed浏览器请求必须由同源挂件发起", HttpStatus.FORBIDDEN);
    }

    /**
     * 处理{@code normalized}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalized(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip().toLowerCase(java.util.Locale.ROOT);
    }
}
