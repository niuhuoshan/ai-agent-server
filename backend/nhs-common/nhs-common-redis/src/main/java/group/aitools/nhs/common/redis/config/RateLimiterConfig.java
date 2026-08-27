package group.aitools.nhs.common.redis.config;

import group.aitools.nhs.common.redis.aspectj.RateLimiterAspect;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConfiguration;

/**
 * 限流功能配置。
 *
 * @author guangxin
 * @date 2023/1/18
 */
@AutoConfiguration(after = RedisConfiguration.class)
public class RateLimiterConfig {

    /**
     * 创建限流切面。
     *
     * @return 限流切面
     */
    @Bean
    public RateLimiterAspect rateLimiterAspect() {
        return new RateLimiterAspect();
    }

}
