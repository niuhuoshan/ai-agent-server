package group.aitools.nhs.platform.common;

import group.aitools.nhs.common.mybatis.utils.IdGeneratorUtil;
import org.springframework.stereotype.Component;

/**
 * 处理{@code nextId}并返回对应结果。
 *
 * 表示Mybatis平台IdGenerator相关的领域对象。
 * Uses the NHS/MyBatis-Plus identifier generator for all platform tables. */
@Component
public final class MybatisPlatformIdGenerator implements PlatformIdGenerator {

    @Override
    public Long nextId() {
        return IdGeneratorUtil.nextLongId();
    }

    /**
     * 处理{@code nextUuid}并返回对应结果。
     *
     * @return 处理结果
     */
    @Override
    public String nextUuid() {
        return IdGeneratorUtil.nextUUID();
    }
}
