package group.aitools.nhs.platform.sandbox.service;

import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 表示沙箱技能Manifest相关的领域对象。
 *
 * Canonical frozen Skill metadata carried by a Sandbox job.
 *
 * <p>The manifest contains identities and hashes only.  File bytes are fetched through the
 * runner job-token endpoint after the job is leased, so a queue row never trusts a mutable
 * application workspace path as the source of Skill content.</p>
 */
public final class SandboxSkillManifest {

    private static final Pattern SKILL_KEY = Pattern.compile("[a-z][a-z0-9._-]{0,127}");
    private static final int MAX_SKILLS = 128;
    private static final int MAX_JSON_BYTES = 256 * 1024;
    private static final Set<String> DEPENDENCY_TYPES = Set.of("python", "node");

    private SandboxSkillManifest() {
    }

    /**
     * 处理{@code fromAttributes}并返回对应结果。
     *
     * @param attributes {@code attributes}参数
     * @param mapper {@code mapper}参数
     * @return 处理结果
     */
    public static Normalized fromAttributes(Map<String, Object> attributes, JsonMapper mapper) {
        return fromAttributes(attributes, null, mapper);
    }

    /**
     * 处理{@code fromAttributes}并返回对应结果。
     *
     * @param attributes {@code attributes}参数
     * @param workspaceKey 工作空间Key参数
     * @param mapper {@code mapper}参数
     * @return 处理结果
     */
    public static Normalized fromAttributes(
        Map<String, Object> attributes,
        String workspaceKey,
        JsonMapper mapper
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        Object raw = attributes == null ? null : attributes.get("resourceBindings");
        if (!(raw instanceof List<?> bindings)) {
            return empty(mapper);
        }
        List<Map<String, Object>> skills = new ArrayList<>();
        for (Object item : bindings) {
            if (!(item instanceof Map<?, ?> rawBinding)
                || !"skill".equals(String.valueOf(rawBinding.get("resourceType")))) {
                continue;
            }
            Map<String, Object> binding = stringMap(rawBinding);
            Map<String, Object> config = map(binding.get("config"));
            Map<String, Object> snapshot = map(config.get("resourceSnapshot"));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("skillId", binding.get("resourceId"));
            entry.put("versionId", snapshot.get("versionId"));
            entry.put("skillKey", snapshot.get("skillKey"));
            entry.put("bundleHash", snapshot.get("fileBundleHash"));
            Object requirements = snapshot.get("runtimeRequirements");
            if (requirements != null) {
                entry.put("runtimeRequirements", requirements);
            }
            skills.add(entry);
        }
        return normalize(skills, workspaceKey, mapper);
    }

    /**
     * 处理{@code fromJson}并返回对应结果。
     *
     * @param json {@code json}参数
     * @param mapper {@code mapper}参数
     * @return 处理结果
     */
    public static Normalized fromJson(String json, JsonMapper mapper) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (json == null || json.isBlank()) {
            return empty(mapper);
        }
        try {
            Object value = mapper.readValue(json, Object.class);
            if (value instanceof Map<?, ?> object) {
                Map<String, Object> root = stringMap(object);
                Object version = root.get("version");
                if (!(version instanceof Number number)
                    || number.doubleValue() != number.longValue() || number.longValue() != 1L) {
                    throw badRequest("Sandbox Skill manifest 版本不支持");
                }
                Object skills = root.get("skills");
                if (!(skills instanceof List<?> list)) {
                    throw badRequest("Sandbox Skill manifest 缺少 skills 数组");
                }
                String workspaceKey = root.get("workspaceKey") instanceof String text
                    ? text : null;
                return normalize(list, workspaceKey, mapper);
            }
            if (value instanceof List<?> list) {
                if (!list.isEmpty()) {
                    throw badRequest("Sandbox Skill manifest 旧版数组格式仅支持空数组");
                }
                return normalize(list, null, mapper);
            }
            throw badRequest("Sandbox Skill manifest 必须是对象");
        } catch (ServiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw badRequest("Sandbox Skill manifest 格式无效");
        }
    }

    /**
     * 处理{@code normalize}并返回对应结果。
     *
     * @param raw {@code raw}参数
     * @param mapper {@code mapper}参数
     * @return 处理结果
     */
    public static Normalized normalize(List<?> raw, JsonMapper mapper) {
        return normalize(raw, null, mapper);
    }

    /**
     * 处理{@code normalize}并返回对应结果。
     *
     * @param raw {@code raw}参数
     * @param workspaceKey 工作空间Key参数
     * @param mapper {@code mapper}参数
     * @return 处理结果
     */
    public static Normalized normalize(
        List<?> raw,
        String workspaceKey,
        JsonMapper mapper
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (raw == null || raw.isEmpty()) {
            return empty(workspaceKey, mapper);
        }
        if (raw.size() > MAX_SKILLS) {
            throw badRequest("Sandbox Skill 数量超过上限");
        }
        List<Map<String, Object>> entries = new ArrayList<>(raw.size());
        java.util.HashSet<String> keys = new java.util.HashSet<>();
        for (Object item : raw) {
            if (!(item instanceof Map<?, ?> rawEntry)) {
                throw badRequest("Sandbox Skill manifest 条目无效");
            }
            Map<String, Object> source = stringMap(rawEntry);
            long skillId = positiveLong(source.get("skillId"), "Skill 资源 ID");
            long versionId = positiveLong(source.get("versionId"), "Skill 版本 ID");
            String skillKey = text(source.get("skillKey"), "Skill 标识");
            if (!SKILL_KEY.matcher(skillKey).matches()) {
                throw badRequest("Skill 标识无效");
            }
            if (!keys.add(skillKey)) {
                throw badRequest("Sandbox Skill manifest 包含重复 Skill");
            }
            Object rawBundleHash = source.containsKey("bundleHash")
                ? source.get("bundleHash") : source.get("fileBundleHash");
            String bundleHash = text(rawBundleHash, "Skill 文件包哈希").toLowerCase();
            if (!bundleHash.matches("[0-9a-f]{64}")) {
                throw badRequest("Skill 文件包哈希无效");
            }
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("skillId", skillId);
            normalized.put("versionId", versionId);
            normalized.put("skillKey", skillKey);
            normalized.put("bundleHash", bundleHash);
            Map<String, Object> requirements = normalizeRequirements(source.get("runtimeRequirements"));
            if (!requirements.isEmpty()) {
                normalized.put("runtimeRequirements", requirements);
            }
            entries.add(normalized);
        }
        entries.sort(Comparator.comparing(item -> String.valueOf(item.get("skillKey"))));
        return encode(entries, workspaceKey, mapper);
    }

    /**
     * 处理{@code normalizeRequirements}并返回对应结果。
     *
     * @param raw {@code raw}参数
     * @return 处理结果
     */
    private static Map<String, Object> normalizeRequirements(Object raw) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> source)) {
            throw badRequest("Skill 运行要求无效");
        }
        Object dependenciesRaw = source.get("dependencies");
        if (dependenciesRaw == null) {
            return Map.of();
        }
        if (!(dependenciesRaw instanceof Map<?, ?> dependencyMap)) {
            throw badRequest("Skill 依赖声明无效");
        }
        Map<String, Object> dependencies = new LinkedHashMap<>();
        for (Map.Entry<?, ?> item : dependencyMap.entrySet()) {
            String type = String.valueOf(item.getKey()).strip().toLowerCase();
            if (!DEPENDENCY_TYPES.contains(type)) {
                throw badRequest("Skill 依赖类型无效");
            }
            if (!(item.getValue() instanceof List<?> values) || values.size() > 32) {
                throw badRequest("Skill 依赖列表无效");
            }
            List<String> normalized = new ArrayList<>();
            for (Object value : values) {
                if (!(value instanceof String text) || text.isBlank() || text.length() > 192) {
                    throw badRequest("Skill 依赖项无效");
                }
                normalized.add(text.strip());
            }
            normalized.sort(String::compareToIgnoreCase);
            dependencies.put(type, List.copyOf(normalized));
        }
        if (dependencies.isEmpty()) {
            return Map.of();
        }
        return Map.of("dependencies", dependencies);
    }

    /**
     * 处理{@code empty}并返回对应结果。
     *
     * @param mapper {@code mapper}参数
     * @return 处理结果
     */
    private static Normalized empty(JsonMapper mapper) {
        return empty(null, mapper);
    }

    /**
     * 处理{@code empty}并返回对应结果。
     *
     * @param workspaceKey 工作空间Key参数
     * @param mapper {@code mapper}参数
     * @return 处理结果
     */
    private static Normalized empty(String workspaceKey, JsonMapper mapper) {
        return encode(List.of(), workspaceKey, mapper);
    }

    /**
     * 处理{@code encode}并返回对应结果。
     *
     * @param entries {@code entries}参数
     * @param workspaceKey 工作空间Key参数
     * @param mapper {@code mapper}参数
     * @return 处理结果
     */
    private static Normalized encode(
        List<Map<String, Object>> entries,
        String workspaceKey,
        JsonMapper mapper
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("version", 1);
            if (workspaceKey != null && !workspaceKey.isBlank()) {
                root.put("workspaceKey", workspaceKey.strip());
            }
            List<Map<String, Object>> skills = entries.stream().map(entry -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("skillId", entry.get("skillId"));
                item.put("versionId", entry.get("versionId"));
                item.put("skillKey", entry.get("skillKey"));
                item.put("fileBundleHash", entry.get("bundleHash"));
                if (entry.containsKey("runtimeRequirements")) {
                    item.put("runtimeRequirements", entry.get("runtimeRequirements"));
                }
                return item;
            }).toList();
            root.put("skills", skills);
            String json = mapper.writeValueAsString(canonicalize(root));
            if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
                throw badRequest("Sandbox Skill manifest 过大");
            }
            return new Normalized(
                List.copyOf(entries), workspaceKey == null ? null : workspaceKey.strip(),
                json, ContentHashing.sha256(json)
            );
        } catch (ServiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ServiceException("Sandbox Skill manifest 无法编码", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 处理{@code stringMap}并返回对应结果。
     *
     * @param source 数据源参数
     * @return 处理结果
     */
    private static Map<String, Object> stringMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    /**
     * 判断{@code onicalize}是否满足要求。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private static Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> sorted = new TreeMap<>();
            raw.forEach((key, item) -> sorted.put(String.valueOf(key), canonicalize(item)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(SandboxSkillManifest::canonicalize).toList();
        }
        return value;
    }

    /**
     * 将输入数据转换为{@code map}。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        return stringMap(raw);
    }

    /**
     * 处理{@code positiveLong}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private static long positiveLong(Object value, String label) {
        if (!(value instanceof Number number)
            || number.doubleValue() != number.longValue() || number.longValue() <= 0) {
            throw badRequest(label + "无效");
        }
        return number.longValue();
    }

    /**
     * 处理{@code text}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private static String text(Object value, String label) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > 256
            || text.indexOf('\0') >= 0) {
            throw badRequest(label + "无效");
        }
        return text.strip();
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

    /**
     * 封装{@code Normalized}相关的不可变数据。
     */
    public record Normalized(
        List<Map<String, Object>> entries,
        String workspaceKey,
        String json,
        String hash
    ) {
        /**
         * 处理{@code empty}并返回对应结果。
         *
         * @return 判断结果，{@code true} 表示条件成立
         */
        public boolean empty() {
            return entries.isEmpty();
        }
    }
}
