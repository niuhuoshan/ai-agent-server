package group.aitools.nhs.common.social.config;

import me.zhyd.oauth.cache.AuthStateCache;
import group.aitools.nhs.common.social.config.properties.SocialProperties;
import group.aitools.nhs.common.social.utils.AuthRedisStateCache;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Social 配置属性
 *
 * @author thiszhc
 */
@AutoConfiguration
@EnableConfigurationProperties(SocialProperties.class)
public class SocialAutoConfiguration {

    /**
     * 注册第三方授权状态缓存实现。
     *
     * @return 授权状态缓存
     */
    @Bean
    public AuthStateCache authStateCache() {
        return new AuthRedisStateCache();
    }

}
