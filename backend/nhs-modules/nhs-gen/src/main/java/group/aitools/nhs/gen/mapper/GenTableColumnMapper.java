package group.aitools.nhs.gen.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import group.aitools.nhs.common.mybatis.core.mapper.BaseMapperPlus;
import group.aitools.nhs.gen.domain.GenTableColumn;

/**
 * 业务字段 数据层
 *
 * @author Lion Li
 */
@InterceptorIgnore(dataPermission = "true", tenantLine = "true")
public interface GenTableColumnMapper extends BaseMapperPlus<GenTableColumn, GenTableColumn> {

}
