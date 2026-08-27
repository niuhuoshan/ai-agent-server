package group.aitools.nhs.platform.task.web;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 表示任务InputDefaults相关的领域对象。
 */
final class TaskInputDefaults {

    /**
     * 创建 {@code TaskInputDefaults} 实例并初始化所需依赖。
     */
    private TaskInputDefaults() {
    }

    /**
     * 将输入数据转换为{@code map}。
     *
     * @param source 数据源参数
     * @return 处理结果
     */
    static <T> Map<String, T> map(Map<String, T> source) {
        return source == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    /**
     * 查询{@code list}列表。
     *
     * @param source 数据源参数
     * @return 符合条件的数据集合
     */
    static <T> List<T> list(List<T> source) {
        return source == null ? List.of() : List.copyOf(source);
    }

    /**
     * 处理{@code lifecycle}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param orchestrationMode {@code orchestrationMode}参数
     * @return 处理结果
     */
    static String lifecycle(String value, String orchestrationMode) {
        if (value != null) {
            return value;
        }
        return "single_agent".equals(orchestrationMode) ? "L1_short_task" : "L2_workflow_task";
    }
}
