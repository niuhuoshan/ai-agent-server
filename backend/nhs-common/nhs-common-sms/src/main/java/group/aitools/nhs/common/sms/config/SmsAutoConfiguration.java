package group.aitools.nhs.common.sms.config;

import group.aitools.nhs.common.sms.core.dao.PlusSmsDao;
import group.aitools.nhs.common.sms.handler.SmsExceptionHandler;
import org.dromara.sms4j.api.dao.SmsDao;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 短信配置类
 *
 * @author AprilWind
 */
@AutoConfiguration(after = {DataRedisAutoConfiguration.class})
public class SmsAutoConfiguration {

    /**
     * 创建 sms4j 使用的短信缓存访问对象。
     *
     * @return 短信缓存访问对象
     */
    @Primary
    @Bean
    public SmsDao smsDao() {
        return new PlusSmsDao();
    }

    /**
     * 异常处理器
     */
    @Bean
    public SmsExceptionHandler smsExceptionHandler() {
        return new SmsExceptionHandler();
    }

}
