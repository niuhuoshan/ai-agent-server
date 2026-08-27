package group.aitools.nhs.platform.operations.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import group.aitools.nhs.platform.operations.domain.PlatformConfiguration;
import group.aitools.nhs.platform.operations.domain.PlatformConfigurationHistory;

import java.util.List;

/**
 * 定义平台配置相关的数据访问契约。
 */
@Mapper
public interface PlatformConfigurationMapper {

    String COLUMNS = """
        id, product_name, product_short_name, logo_url, favicon_url, primary_color,
        platform_timezone, default_locale, watermark_enabled, revision_no,
        update_by, update_time
        """;

    /**
     * 获取当前。
     *
     * @return 处理结果
     */
    @Select("SELECT " + COLUMNS + " FROM agent_platform_configuration WHERE id = 1")
    PlatformConfiguration selectCurrent();

    /**
     * 更新当前。
     *
     * @param value {@code value}参数
     * @param expectedRevision {@code expectedRevision}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_platform_configuration
        SET product_name = #{value.productName},
            product_short_name = #{value.productShortName},
            logo_url = #{value.logoUrl},
            favicon_url = #{value.faviconUrl},
            primary_color = #{value.primaryColor},
            platform_timezone = #{value.platformTimezone},
            default_locale = #{value.defaultLocale},
            watermark_enabled = #{value.watermarkEnabled},
            revision_no = revision_no + 1,
            update_by = #{value.updateBy},
            update_time = #{value.updateTime}
        WHERE id = 1 AND revision_no = #{expectedRevision}
        """)
    int updateCurrent(
        @Param("value") PlatformConfiguration value,
        @Param("expectedRevision") Long expectedRevision
    );

    /**
     * 创建并保存历史记录。
     *
     * @param history 历史记录参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_platform_configuration_history (
            id, configuration_id, product_name, product_short_name, logo_url,
            favicon_url, primary_color, platform_timezone, default_locale,
            watermark_enabled, revision_no, change_reason, changed_by, created_at
        ) VALUES (
            #{id}, #{configurationId}, #{productName}, #{productShortName}, #{logoUrl},
            #{faviconUrl}, #{primaryColor}, #{platformTimezone}, #{defaultLocale},
            #{watermarkEnabled}, #{revisionNo}, #{changeReason}, #{changedBy}, #{createdAt}
        )
        """)
    int insertHistory(PlatformConfigurationHistory history);

    /**
     * 获取历史记录。
     *
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Select("""
        SELECT id, configuration_id, product_name, product_short_name, logo_url,
               favicon_url, primary_color, platform_timezone, default_locale,
               watermark_enabled, revision_no, change_reason, changed_by, created_at
        FROM agent_platform_configuration_history
        WHERE configuration_id = 1
        ORDER BY revision_no DESC
        LIMIT #{limit}
        """)
    List<PlatformConfigurationHistory> selectHistory(@Param("limit") int limit);
}
