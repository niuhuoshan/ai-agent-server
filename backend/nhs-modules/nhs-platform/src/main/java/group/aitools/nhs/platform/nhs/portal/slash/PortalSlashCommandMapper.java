package group.aitools.nhs.platform.nhs.portal.slash;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 获取{@code Visible}。
 *
 * 定义门户Slash命令相关的数据访问契约。
 * Persistence boundary for portal-owned slash commands. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
@Mapper
public interface PortalSlashCommandMapper {

    @Select("""
        SELECT id, label, command, sort_order, created_by, created_at, updated_at, del_flag
        FROM agent_portal_slash_command
        WHERE del_flag = '0'
          AND (created_by = 0 OR created_by = #{userId} OR #{admin} = TRUE)
        ORDER BY CASE WHEN created_by = #{userId} THEN 0 ELSE 1 END, sort_order ASC, id ASC
        LIMIT #{limit}
        """)
    List<PortalSlashCommand> selectVisible(
        @Param("userId") Long userId,
        @Param("admin") boolean admin,
        @Param("limit") int limit
    );

    /**
     * 获取{@code ById}。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    @Select("""
        SELECT id, label, command, sort_order, created_by, created_at, updated_at, del_flag
        FROM agent_portal_slash_command
        WHERE id = #{id} AND del_flag = '0'
        """)
    PortalSlashCommand selectById(@Param("id") Long id);

    /**
     * 创建并保存{@code insert}。
     *
     * @param command 命令参数
     * @return 处理结果
     */
    @Insert("""
        INSERT INTO agent_portal_slash_command (
            id, label, command, sort_order, created_by, created_at, del_flag
        ) VALUES (
            #{id}, #{label}, #{command}, #{sortOrder}, #{createdBy}, #{createdAt}, '0'
        )
        """)
    int insert(PortalSlashCommand command);

    /**
     * 更新{@code update}。
     *
     * @param id 资源标识
     * @param label {@code label}参数
     * @param command 命令参数
     * @param sortOrder {@code sortOrder}参数
     * @param updatedAt {@code updatedAt}参数
     * @param userId 资源标识
     * @param admin {@code admin}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_portal_slash_command
        SET label = #{label}, command = #{command}, sort_order = #{sortOrder},
            updated_at = #{updatedAt}
        WHERE id = #{id} AND del_flag = '0'
          AND (created_by = #{userId} OR #{admin} = TRUE)
        """)
    int update(
        @Param("id") Long id,
        @Param("label") String label,
        @Param("command") String command,
        @Param("sortOrder") int sortOrder,
        @Param("updatedAt") LocalDateTime updatedAt,
        @Param("userId") Long userId,
        @Param("admin") boolean admin
    );

    /**
     * 处理{@code softDelete}并返回对应结果。
     *
     * @param id 资源标识
     * @param now {@code now}参数
     * @param userId 资源标识
     * @param admin {@code admin}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_portal_slash_command
        SET del_flag = '1', updated_at = #{now}
        WHERE id = #{id} AND del_flag = '0'
          AND (created_by = #{userId} OR #{admin} = TRUE)
        """)
    int softDelete(
        @Param("id") Long id,
        @Param("now") LocalDateTime now,
        @Param("userId") Long userId,
        @Param("admin") boolean admin
    );

    /**
     * 更新{@code SortOrder}。
     *
     * @param id 资源标识
     * @param sortOrder {@code sortOrder}参数
     * @param now {@code now}参数
     * @param userId 资源标识
     * @param admin {@code admin}参数
     * @return 处理结果
     */
    @Update("""
        UPDATE agent_portal_slash_command
        SET sort_order = #{sortOrder}, updated_at = #{now}
        WHERE id = #{id} AND del_flag = '0'
          AND (created_by = #{userId} OR #{admin} = TRUE)
        """)
    int updateSortOrder(
        @Param("id") Long id,
        @Param("sortOrder") int sortOrder,
        @Param("now") LocalDateTime now,
        @Param("userId") Long userId,
        @Param("admin") boolean admin
    );
}
