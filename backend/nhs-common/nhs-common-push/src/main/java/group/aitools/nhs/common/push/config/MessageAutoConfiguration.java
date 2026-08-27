package group.aitools.nhs.common.push.config;

import group.aitools.nhs.common.push.properties.MessageProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 统一消息推送公共自动装配。
 *
 * @author Lion Li
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "message", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(MessageProperties.class)
public class MessageAutoConfiguration {
}
