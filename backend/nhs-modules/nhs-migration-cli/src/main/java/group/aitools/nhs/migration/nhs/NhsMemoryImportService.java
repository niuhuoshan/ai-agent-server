package group.aitools.nhs.migration.nhs;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 负责Nhs记忆导入相关的业务编排与领域规则处理。
 */
final class NhsMemoryImportService {

    private static final long MAX_EXPORT_BYTES = 10L * 1024 * 1024 * 1024;
    private static final int MAX_LINE_CHARS = 8 * 1024 * 1024;

    private final Path input;
    private final MigrationTarget target;
    private final JsonCodec json;
    private final MigrationReport report;
    private final long runId;
    private final Map<String, MutableResult> results = new LinkedHashMap<>();

    /**
     * 创建 {@code NhsMemoryImportService} 实例并初始化所需依赖。
     *
     * @param input {@code input}参数
     * @param target {@code target}参数
     * @param json {@code json}参数
     * @param report 报表参数
     * @param runId 资源标识
     */
    NhsMemoryImportService(
        Path input,
        MigrationTarget target,
        JsonCodec json,
        MigrationReport report,
        long runId
    ) {
        this.input = input;
        this.target = target;
        this.json = json;
        this.report = report;
        this.runId = runId;
    }

    /**
     * 处理导入Records并返回对应结果。
     *
     * @return 判断结果，{@code true} 表示条件成立
     * @throws IOException 当处理过程无法正常完成时抛出
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    boolean importRecords() throws IOException, SQLException {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        validateInput();
        try (BufferedReader reader = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
            String line;
            long lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                if (line.length() > MAX_LINE_CHARS) {
                    recordFailure("unknown", "line:" + lineNumber, "export record exceeds 8 MiB");
                    continue;
                }
                importLine(line, lineNumber);
            }
        }
        for (Map.Entry<String, MutableResult> entry : results.entrySet()) {
            MutableResult mutable = entry.getValue();
            MigrationReport.EntityResult result = mutable.toResult(entry.getKey(), json);
            report.add(result);
            target.persistResult(runId, result);
            target.checkpoint(runId, "redis", entry.getKey(), result.sourceHash());
        }
        target.commit();
        return results.values().stream().noneMatch(result -> result.failed > 0);
    }

    /**
     * 处理导入Line相关逻辑。
     *
     * @param line {@code line}参数
     * @param lineNumber {@code lineNumber}参数
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private void importLine(String line, long lineNumber) throws SQLException {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        Map<String, Object> record;
        try {
            record = json.readMap(line);
        } catch (RuntimeException exception) {
            recordFailure("unknown", "line:" + lineNumber, "invalid JSON record");
            return;
        }
        String kind = text(record.get("kind"));
        String userId = text(record.get("userId"));
        String conversationId = text(record.get("conversationId"));
        String sourceId = "conversation".equals(kind)
            ? userId + ":" + conversationId : kind + ":" + userId + ":" + Objects.toString(conversationId, "");
        MutableResult result = results.computeIfAbsent(kind == null ? "unknown" : kind, ignored -> new MutableResult());
        result.source++;
        Map<String, Object> hashPayload = new LinkedHashMap<>(record);
        String declaredHash = text(hashPayload.remove("recordHash"));
        String sourceHash = json.sha256(hashPayload);
        result.sourceHashes.add(sourceHash);
        if (declaredHash == null || !declaredHash.equals(sourceHash)) {
            result.failed++;
            issue("error", "MEMORY_EXPORT_HASH_MISMATCH", kind, sourceId,
                "record hash mismatch at line " + lineNumber);
            return;
        }
        String targetType = "conversation".equals(kind) ? "conversation" : "memory";
        MigrationTarget.PriorMapping prior = target.priorMapping("redis_" + kind, sourceId, targetType);
        if (prior != null && sourceHash.equals(prior.sourceHash())) {
            result.mapped++;
            result.reused++;
            result.targetHashes.add(prior.targetHash());
            target.mapping(runId, "redis_" + kind, sourceId, targetType, prior.targetId(),
                sourceHash, prior.targetHash(), "mapped", null);
            return;
        }
        if (prior != null) {
            result.failed++;
            issue("error", "MEMORY_SOURCE_DRIFT", kind, sourceId,
                "an already imported Redis record changed; review it before replacing private target data");
            target.mapping(runId, "redis_" + kind, sourceId, targetType, null,
                sourceHash, null, "failed", "source drift requires review");
            return;
        }

        Savepoint savepoint = target.savepoint("memory_" + lineNumber);
        try {
            ImportOutcome outcome = switch (kind == null ? "" : kind) {
                case "conversation" -> conversation(record, sourceHash);
                case "ltm" -> longTermMemory(record, sourceHash);
                case "summary" -> summary(record, sourceHash);
                default -> ImportOutcome.skip("unsupported Redis record kind");
            };
            if (outcome.skipped()) {
                result.skipped++;
                target.mapping(runId, "redis_" + kind, sourceId, targetType, null,
                    sourceHash, null, "skipped", outcome.reason());
                issue("warning", "MEMORY_RECORD_SKIPPED", kind, sourceId, outcome.reason());
            } else {
                result.mapped++;
                result.inserted++;
                result.targetHashes.add(outcome.targetHash());
                target.mapping(runId, "redis_" + kind, sourceId, targetType, outcome.targetId(),
                    sourceHash, outcome.targetHash(), "mapped", null);
            }
            target.release(savepoint);
        } catch (Exception exception) {
            target.rollback(savepoint);
            result.failed++;
            String message = safeError(exception);
            target.mapping(runId, "redis_" + kind, sourceId, targetType, null,
                sourceHash, null, "failed", message);
            issue("error", "MEMORY_RECORD_IMPORT_FAILED", kind, sourceId, message);
        }
    }

    /**
     * 处理会话并返回对应结果。
     *
     * @param record {@code record}参数
     * @param sourceHash 数据源Hash参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private ImportOutcome conversation(Map<String, Object> record, String sourceHash) throws SQLException {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        String sourceUserId = required(text(record.get("userId")), "userId");
        String sourceConversationId = required(text(record.get("conversationId")), "conversationId");
        Long userId = target.mappedId("users", sourceUserId, "user");
        if (userId == null) {
            return ImportOutcome.skip("conversation owner has no migrated user mapping");
        }
        String sessionKey = bounded("nhs-redis:" + userId + ":" + sourceConversationId, 128, sourceHash);
        Long existing = target.findId("agent_conversation", "id", "session_key", sessionKey);
        if (existing != null) {
            return new ImportOutcome(existing, false, null, json.sha256(Map.of("sessionKey", sessionKey, "existing", true)));
        }
        List<Map<String, Object>> messages = maps(record.get("messages"));
        String title = messages.stream()
            .filter(message -> "user".equals(text(message.get("role"))))
            .map(message -> text(message.get("content")))
            .filter(Objects::nonNull)
            .findFirst().orElse("Migrated Nhs conversation");
        Long agentId = resolveAgent(messages);
        Long agentVersionId = agentId == null ? null : JdbcSupport.scalar(target.connection(),
            "SELECT id FROM agent_definition_version WHERE agent_id=? ORDER BY version_no DESC LIMIT 1",
            Long.class, agentId);
        long id = target.nextId("agent_conversation", "id");
        Instant lastMessageAt = messages.stream().map(message -> instant(message.get("timestamp")))
            .filter(Objects::nonNull).max(Instant::compareTo).orElse(Instant.now());
        target.insert("agent_conversation", values(
            "id", id,
            "user_id", userId,
            "agent_id", agentId,
            "agent_version_id", agentVersionId,
            "title", truncate(title, 255),
            "visibility", "private",
            "status", "archived",
            "session_key", sessionKey,
            "last_message_at", lastMessageAt,
            "metadata_json", new MigrationTarget.JsonValue(Map.of(
                "legacySource", "nhs_redis",
                "legacyConversationId", sourceConversationId,
                "readOnly", true
            )),
            "create_by", userId,
            "create_time", messages.isEmpty() ? Instant.now() : Objects.requireNonNullElse(instant(messages.getFirst().get("timestamp")), Instant.now()),
            "del_flag", "0",
            "extra_json", new MigrationTarget.JsonValue(Map.of("migrationRunId", runId))
        ));
        int sequence = 0;
        for (Map<String, Object> message : messages) {
            String role = text(message.get("role"));
            if (!Set.of("user", "assistant", "tool", "system").contains(role)) {
                role = "system";
            }
            Long messageAgentId = "assistant".equals(role) ? resolveAgent(List.of(message)) : null;
            Long messageAgentVersionId = messageAgentId == null ? null : JdbcSupport.scalar(target.connection(),
                "SELECT id FROM agent_definition_version WHERE agent_id=? ORDER BY version_no DESC LIMIT 1",
                Long.class, messageAgentId);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("legacyReadOnly", true);
            metadata.put("legacyTimestamp", text(message.get("timestamp")));
            metadata.put("files", message.getOrDefault("files", List.of()));
            metadata.put("hasDataOutput", bool(message.get("has_data_output")));
            metadata.put("reasoningArchived", message.get("reasoning_content") != null);
            target.insert("agent_conversation_message", values(
                "id", target.nextId("agent_conversation_message", "id"),
                "conversation_id", id,
                "seq_no", ++sequence,
                "trace_id", truncate(text(message.get("trace_id")), 64),
                "role", role,
                "content", text(message.get("content")),
                "content_json", new MigrationTarget.JsonValue(metadata),
                "agent_id", messageAgentId,
                "agent_version_id", messageAgentVersionId,
                "status", "completed",
                "prompt_tokens", nonNegative(message.get("prompt_tokens")),
                "completion_tokens", nonNegative(message.get("completion_tokens")),
                "total_tokens", nonNegative(message.get("prompt_tokens")) + nonNegative(message.get("completion_tokens")),
                "created_at", Objects.requireNonNullElse(instant(message.get("timestamp")), Instant.now())
            ));
        }
        return new ImportOutcome(id, false, null, json.sha256(Map.of(
            "sessionKey", sessionKey, "messageCount", sequence, "visibility", "private"
        )));
    }

    /**
     * 处理longTerm记忆并返回对应结果。
     *
     * @param record {@code record}参数
     * @param sourceHash 数据源Hash参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private ImportOutcome longTermMemory(Map<String, Object> record, String sourceHash) throws SQLException {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        Long userId = mappedUser(record);
        if (userId == null) {
            return ImportOutcome.skip("long-term memory owner has no migrated user mapping");
        }
        Map<String, Object> values = map(record.get("values"));
        if (values.isEmpty()) {
            return ImportOutcome.skip("long-term memory hash is empty");
        }
        Long firstId = null;
        List<String> hashes = new ArrayList<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String content = entry.getKey() + ": " + Objects.toString(entry.getValue(), "");
            long id = insertMemory(
                userId,
                "nhs-ltm-" + sourceHash.substring(0, 12) + "-" + json.sha256(entry.getKey()).substring(0, 8),
                "preference",
                content,
                record,
                Map.of("legacyField", entry.getKey(), "legacyKind", "ltm")
            );
            if (firstId == null) {
                firstId = id;
            }
            hashes.add(json.sha256(content));
        }
        return new ImportOutcome(firstId, false, null, json.aggregateHash(hashes));
    }

    /**
     * 处理{@code summary}并返回对应结果。
     *
     * @param record {@code record}参数
     * @param sourceHash 数据源Hash参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private ImportOutcome summary(Map<String, Object> record, String sourceHash) throws SQLException {
        Long userId = mappedUser(record);
        if (userId == null) {
            return ImportOutcome.skip("summary owner has no migrated user mapping");
        }
        Map<String, Object> values = map(record.get("values"));
        String content = text(values.get("summary"));
        if (content == null) {
            return ImportOutcome.skip("summary record has no summary text");
        }
        long id = insertMemory(
            userId,
            "nhs-summary-" + sourceHash.substring(0, 16),
            "summary",
            content,
            record,
            Map.of(
                "legacyKind", "summary",
                "legacyConversationId", Objects.toString(record.get("conversationId"), ""),
                "legacyTitle", Objects.toString(values.get("title"), "")
            )
        );
        return new ImportOutcome(id, false, null, json.sha256(Map.of("content", content, "reviewStatus", "pending")));
    }

    /**
     * 创建并保存记忆。
     *
     * @param userId 资源标识
     * @param memoryKey 记忆Key参数
     * @param memoryType 业务类型
     * @param content 待处理内容
     * @param record {@code record}参数
     * @param metadata 元数据参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private long insertMemory(
        long userId,
        String memoryKey,
        String memoryType,
        String content,
        Map<String, Object> record,
        Map<String, Object> metadata
    ) throws SQLException {
        Long existing = JdbcSupport.scalar(target.connection(), """
            SELECT id FROM agent_memory
             WHERE scope_type='user' AND scope_id=? AND memory_key=? AND del_flag='0'
             LIMIT 1
            """, Long.class, userId, memoryKey);
        if (existing != null) {
            return existing;
        }
        long id = target.nextId("agent_memory", "id");
        long ttl = number(record.get("ttl"));
        target.insert("agent_memory", values(
            "id", id,
            "scope_type", "user",
            "scope_id", userId,
            "memory_type", memoryType,
            "content", content,
            "source_type", "manual",
            "source_id", null,
            "confidence", null,
            "sensitive_level", "sensitive",
            "review_status", "pending",
            "expires_at", ttl > 0 ? Instant.now().plusSeconds(ttl) : null,
            "created_by", userId,
            "created_at", Instant.now(),
            "memory_key", truncate(memoryKey, 128),
            "content_hash", json.sha256(content),
            "metadata_json", new MigrationTarget.JsonValue(metadata),
            "revision_no", 1,
            "del_flag", "0"
        ));
        return id;
    }

    /**
     * 将输入数据转换为ped用户。
     *
     * @param record {@code record}参数
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private Long mappedUser(Map<String, Object> record) throws SQLException {
        String sourceUserId = text(record.get("userId"));
        return sourceUserId == null ? null : target.mappedId("users", sourceUserId, "user");
    }

    /**
     * 获取智能体。
     *
     * @param messages 待处理内容
     * @return 处理结果
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private Long resolveAgent(List<Map<String, Object>> messages) throws SQLException {
        String agentName = messages.stream().map(message -> text(message.get("agent_name")))
            .filter(Objects::nonNull).findFirst().orElse(null);
        if (agentName == null) {
            return null;
        }
        return JdbcSupport.scalar(target.connection(), """
            SELECT id FROM agent_definition
             WHERE del_flag='0' AND (agent_key=? OR name=? OR extra_json->>'legacyName'=? )
             ORDER BY id LIMIT 1
            """, Long.class, agentName, agentName, agentName);
    }

    /**
     * 校验{@code Input}，并在条件不满足时终止处理。
     *
     * @throws IOException 当处理过程无法正常完成时抛出
     */
    private void validateInput() throws IOException {
        if (!Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("memory export is not a regular file: " + input);
        }
        long size = Files.size(input);
        if (size <= 0 || size > MAX_EXPORT_BYTES) {
            throw new IllegalArgumentException("memory export must be between 1 byte and 10 GiB");
        }
    }

    /**
     * 处理{@code recordFailure}相关逻辑。
     *
     * @param kind {@code kind}参数
     * @param sourceId 资源标识
     * @param message 待处理内容
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private void recordFailure(String kind, String sourceId, String message) throws SQLException {
        MutableResult result = results.computeIfAbsent(kind, ignored -> new MutableResult());
        result.source++;
        result.failed++;
        issue("error", "MEMORY_EXPORT_RECORD_INVALID", kind, sourceId, message);
    }

    /**
     * 判断{@code sue}是否满足要求。
     *
     * @param severity {@code severity}参数
     * @param code {@code code}参数
     * @param entity {@code entity}参数
     * @param sourceId 资源标识
     * @param summary {@code summary}参数
     * @throws SQLException 当处理过程无法正常完成时抛出
     */
    private void issue(String severity, String code, String entity, String sourceId, String summary) throws SQLException {
        MigrationReport.Issue issue = new MigrationReport.Issue(severity, code, entity, sourceId, summary);
        report.issue(severity, code, entity, sourceId, summary);
        target.persistIssue(runId, issue);
    }

    /**
     * 处理{@code bounded}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param limit 数量上限
     * @param hash {@code hash}参数
     * @return 处理结果
     */
    private String bounded(String value, int limit, String hash) {
        return value.length() <= limit ? value : value.substring(0, limit - 13) + "-" + hash.substring(0, 12);
    }

    /**
     * 处理{@code safeError}并返回对应结果。
     *
     * @param exception {@code exception}参数
     * @return 处理结果
     */
    private String safeError(Exception exception) {
        String value = exception.getMessage();
        if (value == null || value.isBlank()) {
            value = exception.getClass().getSimpleName();
        }
        return truncate(value.replace('\n', ' '), 1000);
    }

    /**
     * 校验{@code d}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }

    /**
     * 处理{@code text}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String result = String.valueOf(value).strip();
        return result.isEmpty() ? null : result;
    }

    /**
     * 处理{@code bool}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean bool(Object value) {
        return value instanceof Boolean bool ? bool : "true".equalsIgnoreCase(Objects.toString(value, ""));
    }

    /**
     * 处理{@code nonNegative}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private int nonNegative(Object value) {
        long number = number(value);
        return number < 0 || number > Integer.MAX_VALUE ? 0 : (int) number;
    }

    /**
     * 处理{@code number}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0 : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    /**
     * 处理{@code instant}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Instant instant(Object value) {
        String text = text(value);
        if (text == null) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (RuntimeException ignored) {
            try {
                return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC);
            } catch (RuntimeException invalid) {
                return null;
            }
        }
    }

    /**
     * 将输入数据转换为{@code map}。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    /**
     * 将输入数据转换为{@code s}。
     *
     * @param value {@code value}参数
     * @return 符合条件的数据集合
     */
    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(Map.class::isInstance).map(this::map).toList();
    }

    /**
     * 处理{@code truncate}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param limit 数量上限
     * @return 处理结果
     */
    private String truncate(String value, int limit) {
        return value == null || value.length() <= limit ? value : value.substring(0, limit);
    }

    /**
     * 处理{@code values}并返回对应结果。
     *
     * @param entries {@code entries}参数
     * @return 处理结果
     */
    private Map<String, Object> values(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put(String.valueOf(entries[index]), entries[index + 1]);
        }
        return result;
    }

    /**
     * 封装导入Outcome相关的不可变数据。
     */
    private record ImportOutcome(Long targetId, boolean skipped, String reason, String targetHash) {
        /**
         * 处理{@code skip}并返回对应结果。
         *
         * @param reason {@code reason}参数
         * @return 处理结果
         */
        static ImportOutcome skip(String reason) {
            return new ImportOutcome(null, true, reason, null);
        }
    }

    /**
     * 表示{@code Mutable}操作的返回数据。
     */
    private static final class MutableResult {
        private long source;
        private long mapped;
        private long inserted;
        private long reused;
        private long skipped;
        private long failed;
        private final List<String> sourceHashes = new ArrayList<>();
        private final List<String> targetHashes = new ArrayList<>();

        /**
         * 将输入数据转换为结果。
         *
         * @param kind {@code kind}参数
         * @param json {@code json}参数
         * @return 处理结果
         */
        private MigrationReport.EntityResult toResult(String kind, JsonCodec json) {
            return new MigrationReport.EntityResult(
                "redis_" + kind,
                "load",
                failed == 0 ? "passed" : "failed",
                source,
                mapped,
                inserted,
                reused,
                skipped,
                failed,
                json.aggregateHash(sourceHashes),
                json.aggregateHash(targetHashes),
                Map.of("source", "Nhs Redis export", "privateData", true)
            );
        }
    }
}
