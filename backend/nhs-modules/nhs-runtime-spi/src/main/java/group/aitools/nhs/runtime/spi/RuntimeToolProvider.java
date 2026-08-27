package group.aitools.nhs.runtime.spi;

import java.util.List;
import java.util.Map;
import java.nio.file.Path;

/**
 * 定义运行时工具相关的处理能力契约。
 * Platform-side tool gateway exposed to a runtime adapter without creating a module cycle. */
public interface RuntimeToolProvider {

    /**
 * 处理{@code begin}相关逻辑。
 * Called once before an AgentScope invocation materializes its frozen tools. */
    default void begin(AgentRunRequest request) {
    }

    /**
 * 处理{@code mount}相关逻辑。
 *
     * Materializes files declared by the frozen runtime snapshot into the isolated workspace.
     * Implementations must fail closed when a snapshot hash or path is invalid.  The default is
     * intentionally a no-op so older embedders that do not persist Skill bundles remain usable.
     */
    default void mount(AgentRunRequest request, Path workspace) {
    }

    /**
     * 获取{@code resolve}。
     *
     * @param request 请求参数
     * @return 符合条件的数据集合
     */
    List<RuntimeToolDefinition> resolve(AgentRunRequest request);

    /**
     * 执行{@code invoke}相关的处理流程。
     *
     * @param request 请求参数
     * @param toolId 资源标识
     * @param arguments {@code arguments}参数
     * @return 处理结果
     */
    Object invoke(AgentRunRequest request, Long toolId, Map<String, Object> arguments);

    /**
 * 处理{@code end}相关逻辑。
 * Called exactly once when the owning AgentScope invocation terminates or fails to build. */
    default void end(AgentRunRequest request) {
    }

    /**
     * 处理{@code empty}并返回对应结果。
     *
     * @return 处理结果
     */
    static RuntimeToolProvider empty() {
        return new RuntimeToolProvider() {
            /**
             * 获取{@code resolve}。
             *
             * @param request 请求参数
             * @return 符合条件的数据集合
             */
            @Override
            public List<RuntimeToolDefinition> resolve(AgentRunRequest request) {
                return List.of();
            }

            /**
             * 执行{@code invoke}相关的处理流程。
             *
             * @param request 请求参数
             * @param toolId 资源标识
             * @param arguments {@code arguments}参数
             * @return 处理结果
             */
            @Override
            public Object invoke(AgentRunRequest request, Long toolId, Map<String, Object> arguments) {
                throw new IllegalStateException("runtime tool provider is not configured");
            }
        };
    }
}
