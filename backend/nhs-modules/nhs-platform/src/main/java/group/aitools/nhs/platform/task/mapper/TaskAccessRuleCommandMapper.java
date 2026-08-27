package group.aitools.nhs.platform.task.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 创建并保存{@code CreatorRule}。
 *
 * 定义任务AccessRule命令相关的数据访问契约。
 * Writes explicit ACLs for restricted tasks and artifacts. */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface TaskAccessRuleCommandMapper {

    @Insert("""
        INSERT INTO task_access_rule (
            id, task_id, artifact_id, subject_type, subject_id, subject_key,
            action, effect, created_by, created_at
        ) VALUES (
            #{id}, #{taskId}, NULL, 'user', #{userId}, NULL,
            #{action}, 'allow', #{userId}, #{createdAt}
        )
        ON CONFLICT DO NOTHING
        """)
    int insertCreatorRule(
        @Param("id") Long id,
        @Param("taskId") Long taskId,
        @Param("userId") Long userId,
        @Param("action") String action,
        @Param("createdAt") LocalDateTime createdAt
    );
}
