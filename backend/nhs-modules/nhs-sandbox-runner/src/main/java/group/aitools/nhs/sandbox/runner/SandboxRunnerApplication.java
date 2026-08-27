package group.aitools.nhs.sandbox.runner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 启动并初始化沙箱Runner应用运行环境。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SandboxRunnerApplication {

    /**
     * 处理{@code main}相关逻辑。
     *
     * @param args {@code args}参数
     */
    public static void main(String[] args) {
        SpringApplication.run(SandboxRunnerApplication.class, args);
    }
}
