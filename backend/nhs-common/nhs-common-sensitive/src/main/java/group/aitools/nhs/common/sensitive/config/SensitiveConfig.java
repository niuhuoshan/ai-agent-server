package group.aitools.nhs.common.sensitive.config;

import group.aitools.nhs.common.sensitive.handler.SensitiveJsonFieldProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 脱敏模块配置。
 */
@AutoConfiguration
public class SensitiveConfig {

    /**
     * 创建脱敏 JSON 字段处理器。
     *
     * @return 脱敏 JSON 字段处理器
     */
    @Bean
    public SensitiveJsonFieldProcessor sensitiveJsonFieldProcessor() {
        return new SensitiveJsonFieldProcessor();
    }

}
