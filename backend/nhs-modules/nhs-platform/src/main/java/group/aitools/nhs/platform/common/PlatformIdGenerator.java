package group.aitools.nhs.platform.common;

/**
 * 处理{@code nextId}并返回对应结果。
 *
 * 定义平台IdGenerator相关能力的服务契约。
 * Generates platform business identifiers without exposing the persistence implementation. */
public interface PlatformIdGenerator {

    Long nextId();

    /**
     * 处理{@code nextUuid}并返回对应结果。
     *
     * @return 处理结果
     */
    String nextUuid();
}
