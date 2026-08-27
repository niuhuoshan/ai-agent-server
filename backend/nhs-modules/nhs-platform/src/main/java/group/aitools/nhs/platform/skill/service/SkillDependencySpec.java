package group.aitools.nhs.platform.skill.service;

import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import group.aitools.nhs.platform.common.ContentHashing;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 表示技能DependencySpec相关的领域对象。
 * Validates reproducible, non-shell Skill package declarations. */
final class SkillDependencySpec {

    private static final Set<String> TYPES = Set.of("python", "node");
    private static final Pattern PYTHON = Pattern.compile(
        "[A-Za-z0-9][A-Za-z0-9._-]{0,127}==[A-Za-z0-9][A-Za-z0-9._+~-]{0,63}"
    );
    private static final Pattern NODE = Pattern.compile(
        "(?:@[A-Za-z0-9._-]{1,64}/)?[A-Za-z0-9._-]{1,128}@[0-9][A-Za-z0-9._+~-]{0,63}"
    );
    private static final int MAX_ITEMS_PER_TYPE = 32;

    /**
     * 创建 {@code SkillDependencySpec} 实例并初始化所需依赖。
     */
    private SkillDependencySpec() {
    }

    /**
 * 处理{@code normalize}并返回对应结果。
 * Returns a sorted immutable declaration map suitable for a version snapshot. */
    static Map<String, Object> normalize(Object source) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (source == null) {
            return Map.of();
        }
        if (!(source instanceof Map<?, ?> raw) || raw.size() > TYPES.size()) {
            throw badRequest("技能依赖必须是 python/node 数组对象");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            String type = String.valueOf(entry.getKey()).strip().toLowerCase(Locale.ROOT);
            if (!TYPES.contains(type)) {
                throw badRequest("技能依赖类型不受支持：" + type);
            }
            Object value = entry.getValue();
            if (!(value instanceof List<?> values) || values.size() > MAX_ITEMS_PER_TYPE) {
                throw badRequest("技能 " + type + " 依赖必须是最多 32 项的数组");
            }
            List<String> normalized = new ArrayList<>(values.size());
            for (Object item : values) {
                if (!(item instanceof String text) || text.isBlank() || text.length() > 192) {
                    throw badRequest("技能 " + type + " 依赖项无效");
                }
                String spec = text.strip();
                Pattern pattern = "python".equals(type) ? PYTHON : NODE;
                if (!pattern.matcher(spec).matches()) {
                    throw badRequest(
                        "技能 " + type + " 依赖必须固定版本且不能包含 URL、参数或命令选项：" + spec
                    );
                }
                normalized.add(spec);
            }
            normalized.sort(String::compareToIgnoreCase);
            result.put(type, List.copyOf(normalized));
        }
        return Map.copyOf(result);
    }

    /**
     * 查询{@code s}列表。
     *
     * @param source 数据源参数
     * @return 处理结果
     */
    @SuppressWarnings("unchecked")
    static Map<String, List<String>> lists(Object source) {
        Map<String, Object> normalized = normalize(source);
        Map<String, List<String>> result = new LinkedHashMap<>();
        normalized.forEach((key, value) -> result.put(key, (List<String>) value));
        return Map.copyOf(result);
    }

    /**
 * 判断{@code h}是否满足要求。
 *
     * Computes the canonical hash used by both the explicit installer and runtime mounting.
     * Keeping this in one place prevents a runtime snapshot from accidentally looking up a
     * different cache entry because one side serialized map keys in a different order.
     */
    static String hash(Map<String, List<String>> dependencies, JsonMapper jsonMapper) {
        try {
            return ContentHashing.sha256(jsonMapper.writeValueAsString(new TreeMap<>(dependencies)));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("技能依赖声明无法生成哈希", exception);
        }
    }

    /**
     * 处理{@code badRequest}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private static ServiceException badRequest(String message) {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }
}
