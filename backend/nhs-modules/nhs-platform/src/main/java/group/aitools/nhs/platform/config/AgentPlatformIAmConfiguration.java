package group.aitools.nhs.platform.config;

import group.aitools.nhs.platform.iam.service.AuthorizationService;
import group.aitools.nhs.platform.iam.service.PermissionSnapshotResolver;
import group.aitools.nhs.platform.iam.service.TaskVisibilityService;
import group.aitools.nhs.platform.iam.service.impl.DefaultAuthorizationService;
import group.aitools.nhs.platform.iam.service.impl.DefaultTaskVisibilityService;
import group.aitools.nhs.platform.iam.service.impl.EmptyPermissionSnapshotResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 处理权限快照Resolver并返回对应结果。
 *
 * 配置智能体平台Am相关组件及其运行参数。
 * Default platform IAM wiring; database-backed resolvers can replace these beans later. */
@Configuration(proxyBeanMethods = false)
public class AgentPlatformIAmConfiguration {

    @Bean
    @ConditionalOnMissingBean(PermissionSnapshotResolver.class)
    public PermissionSnapshotResolver permissionSnapshotResolver() {
        return new EmptyPermissionSnapshotResolver();
    }

    /**
     * 处理授权Service并返回对应结果。
     *
     * @param resolver {@code resolver}参数
     * @return 处理结果
     */
    @Bean
    @ConditionalOnMissingBean(AuthorizationService.class)
    public AuthorizationService authorizationService(PermissionSnapshotResolver resolver) {
        return new DefaultAuthorizationService(resolver);
    }

    /**
     * 处理任务VisibilityService并返回对应结果。
     *
     * @param authorizationService 授权Service参数
     * @return 处理结果
     */
    @Bean
    @ConditionalOnMissingBean(TaskVisibilityService.class)
    public TaskVisibilityService taskVisibilityService(AuthorizationService authorizationService) {
        return new DefaultTaskVisibilityService(authorizationService);
    }
}
