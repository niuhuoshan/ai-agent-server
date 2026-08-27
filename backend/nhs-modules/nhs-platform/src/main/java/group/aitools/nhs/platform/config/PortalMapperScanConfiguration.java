package group.aitools.nhs.platform.config;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * 配置门户MapperScan相关组件及其运行参数。
 * Registers migrated portal mappers that intentionally live beside their adapters. */
@Configuration(proxyBeanMethods = false)
@MapperScan(
    basePackages = {
        "group.aitools.nhs.platform.portal.dashboard.persistence",
        "group.aitools.nhs.platform.nhs.portal.chatbi",
        "group.aitools.nhs.platform.nhs.portal.example",
        "group.aitools.nhs.platform.nhs.portal.slash"
    },
    annotationClass = Mapper.class
)
public class PortalMapperScanConfiguration {
}
