package group.aitools.nhs;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

/**
 * web容器中进行部署
 *
 * @author Lion Li
 */
public class NhsServletInitializer extends SpringBootServletInitializer {

    /**
     * 配置外部 Web 容器启动源。
     *
     * @param application Spring 应用构建器
     * @return 配置后的 Spring 应用构建器
     */
    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder application) {
        return application.sources(NhsApplication.class);
    }

}
