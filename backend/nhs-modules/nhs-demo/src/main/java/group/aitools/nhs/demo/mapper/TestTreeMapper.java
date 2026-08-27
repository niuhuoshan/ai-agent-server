package group.aitools.nhs.demo.mapper;

import group.aitools.nhs.common.mybatis.annotation.DataColumn;
import group.aitools.nhs.common.mybatis.annotation.DataPermission;
import group.aitools.nhs.common.mybatis.core.mapper.BaseMapperPlus;
import group.aitools.nhs.demo.domain.TestTree;
import group.aitools.nhs.demo.domain.vo.TestTreeVo;

/**
 * 测试树表Mapper接口
 *
 * @author Lion Li
 * @date 2021-07-26
 */
@DataPermission({
    @DataColumn(key = "deptName", value = "dept_id"),
    @DataColumn(key = "userName", value = "user_id")
})
public interface TestTreeMapper extends BaseMapperPlus<TestTree, TestTreeVo> {

}
