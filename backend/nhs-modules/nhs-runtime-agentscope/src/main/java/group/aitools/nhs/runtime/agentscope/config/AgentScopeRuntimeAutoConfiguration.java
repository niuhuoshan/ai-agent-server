package group.aitools.nhs.runtime.agentscope.config;

import group.aitools.nhs.runtime.agentscope.AgentScopeEventMapper;
import group.aitools.nhs.runtime.agentscope.AgentScopeInvocationFactory;
import group.aitools.nhs.runtime.agentscope.AgentScopeRuntimeAdapter;
import group.aitools.nhs.runtime.agentscope.AgentScopeWorkspaceResolver;
import group.aitools.nhs.runtime.agentscope.DefaultAgentScopeInvocationFactory;
import group.aitools.nhs.runtime.agentscope.DatabaseModelCredentialResolver;
import group.aitools.nhs.runtime.agentscope.RuntimeCredentialResolver;
import group.aitools.nhs.runtime.spi.AgentRunRequestResolver;
import group.aitools.nhs.runtime.spi.AgentRuntime;
import group.aitools.nhs.runtime.spi.RuntimeToolProvider;
import group.aitools.nhs.runtime.spi.RuntimeKnowledgeProvider;
import group.aitools.nhs.runtime.spi.RuntimeMemoryProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.postgresql.state.PostgresAgentStateStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

/**
 * 配置智能体范围运行时Auto相关组件及其运行参数。
 */
@AutoConfiguration
@EnableConfigurationProperties(AgentScopeRuntimeProperties.class)
@ConditionalOnProperty(
    prefix = "agent.runtime.agentscope",
    name = "enabled",
    havingValue = "true"
)
public class AgentScopeRuntimeAutoConfiguration {

    /**
     * 处理智能体范围JacksonObjectMapper并返回对应结果。
     *
     * @return 处理结果
     */
    @Bean("agentScopeJacksonObjectMapper")
    @ConditionalOnMissingBean(name = "agentScopeJacksonObjectMapper")
    public ObjectMapper agentScopeJacksonObjectMapper() {
        return new ObjectMapper();
    }

    /**
     * 执行time凭据Resolver相关的处理流程。
     *
     * @param dataSource 数据数据源参数
     * @return 处理结果
     */
    @Bean
    @ConditionalOnMissingBean(RuntimeCredentialResolver.class)
    public RuntimeCredentialResolver runtimeCredentialResolver(DataSource dataSource) {
        return new DatabaseModelCredentialResolver(dataSource);
    }

    /**
     * 执行time工具提供方相关的处理流程。
     *
     * @return 处理结果
     */
    @Bean
    @ConditionalOnMissingBean(RuntimeToolProvider.class)
    public RuntimeToolProvider runtimeToolProvider() {
        return RuntimeToolProvider.empty();
    }

    /**
     * 执行time知识库提供方相关的处理流程。
     *
     * @return 处理结果
     */
    @Bean
    @ConditionalOnMissingBean(RuntimeKnowledgeProvider.class)
    public RuntimeKnowledgeProvider runtimeKnowledgeProvider() {
        return RuntimeKnowledgeProvider.empty();
    }

    /**
     * 执行time记忆提供方相关的处理流程。
     *
     * @return 处理结果
     */
    @Bean
    @ConditionalOnMissingBean(RuntimeMemoryProvider.class)
    public RuntimeMemoryProvider runtimeMemoryProvider() {
        return RuntimeMemoryProvider.empty();
    }

    /**
     * 处理智能体范围StateStore并返回对应结果。
     *
     * @param dataSource 数据数据源参数
     * @param properties {@code properties}参数
     * @return 处理结果
     */
    @Bean
    @ConditionalOnMissingBean(AgentStateStore.class)
    public AgentStateStore agentScopeStateStore(
        DataSource dataSource,
        AgentScopeRuntimeProperties properties
    ) {
        return new PostgresAgentStateStore(
            dataSource,
            properties.getStateSchema(),
            properties.getStateTable(),
            false
        );
    }

    /**
     * 处理智能体范围工作空间Resolver并返回对应结果。
     *
     * @param properties {@code properties}参数
     * @return 处理结果
     */
    @Bean
    @ConditionalOnMissingBean(AgentScopeWorkspaceResolver.class)
    public AgentScopeWorkspaceResolver agentScopeWorkspaceResolver(
        AgentScopeRuntimeProperties properties
    ) {
        return new AgentScopeWorkspaceResolver(properties.getWorkspaceRoot());
    }

    /**
     * 处理智能体范围调用Factory并返回对应结果。
     *
     * @param credentialResolver 凭据Resolver参数
     * @param runRequestResolver {@code runRequestResolver}参数
     * @param stateStore {@code stateStore}参数
     * @param workspaceResolver 工作空间Resolver参数
     * @param toolProvider 工具提供方参数
     * @param knowledgeProvider 知识库提供方参数
     * @param memoryProvider 记忆提供方参数
     * @param objectMapper {@code objectMapper}参数
     * @param properties {@code properties}参数
     * @return 处理结果
     */
    @Bean
    @ConditionalOnMissingBean(AgentScopeInvocationFactory.class)
    public AgentScopeInvocationFactory agentScopeInvocationFactory(
        RuntimeCredentialResolver credentialResolver,
        AgentRunRequestResolver runRequestResolver,
        AgentStateStore stateStore,
        AgentScopeWorkspaceResolver workspaceResolver,
        RuntimeToolProvider toolProvider,
        RuntimeKnowledgeProvider knowledgeProvider,
        RuntimeMemoryProvider memoryProvider,
        @Qualifier("agentScopeJacksonObjectMapper") ObjectMapper objectMapper,
        AgentScopeRuntimeProperties properties
    ) {
        return new DefaultAgentScopeInvocationFactory(
            credentialResolver,
            runRequestResolver,
            stateStore,
            workspaceResolver,
            toolProvider,
            knowledgeProvider,
            memoryProvider,
            objectMapper,
            properties.getMaxWorkspaceFileSizeMb(),
            properties.isAllowInsecureModelEndpoints()
        );
    }

    /**
     * 处理智能体运行时并返回对应结果。
     *
     * @param invocationFactory 调用Factory参数
     * @param objectMapper {@code objectMapper}参数
     * @return 处理结果
     */
    @Bean
    @ConditionalOnMissingBean(AgentRuntime.class)
    public AgentRuntime agentRuntime(
        AgentScopeInvocationFactory invocationFactory,
        @Qualifier("agentScopeJacksonObjectMapper") ObjectMapper objectMapper
    ) {
        return new AgentScopeRuntimeAdapter(
            invocationFactory,
            new AgentScopeEventMapper(objectMapper)
        );
    }
}
