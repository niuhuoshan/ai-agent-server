package group.aitools.nhs.common.excel.annotation;

import group.aitools.nhs.common.excel.core.ExcelOptionsProvider;

import java.lang.annotation.*;

/**
 * Excel动态下拉选项注解
 *
 * @author Angus
 */
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface ExcelDynamicOptions {

    /**
     * 提供者类全限定名
     * <p>
     * {@link group.aitools.nhs.common.excel.core.ExcelOptionsProvider} 接口实现类 class
     */
    Class<? extends ExcelOptionsProvider> providerClass();
}
