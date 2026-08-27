package group.aitools.nhs.sandbox.runner.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * 配置{@code RunnerJson}相关组件及其运行参数。
 */
@Configuration
public class RunnerJsonConfiguration {

    /**
     * 执行{@code nerJsonMapper}相关的处理流程。
     *
     * @return 处理结果
     */
    @Bean
    JsonMapper runnerJsonMapper() {
        return JsonMapper.builder().findAndAddModules().build();
    }
}
