package group.aitools.nhs.migration.nhs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 表示迁移报表相关的领域对象。
 */
final class MigrationReport {

    private final JsonCodec json;
    private final String command;
    private final String runKey;
    private final Instant startedAt = Instant.now();
    private final List<EntityResult> entities = new ArrayList<>();
    private final List<Issue> issues = new ArrayList<>();
    private Long runId;
    private Instant finishedAt;
    private String status = "running";

    /**
     * 创建 {@code MigrationReport} 实例并初始化所需依赖。
     *
     * @param json {@code json}参数
     * @param command 命令参数
     * @param runKey {@code runKey}参数
     */
    MigrationReport(JsonCodec json, String command, String runKey) {
        this.json = json;
        this.command = command;
        this.runKey = runKey;
    }

    /**
     * 执行{@code Id}相关的处理流程。
     *
     * @param value {@code value}参数
     */
    void runId(Long value) {
        runId = value;
    }

    /**
     * 创建并保存{@code add}。
     *
     * @param result 结果参数
     */
    void add(EntityResult result) {
        entities.add(result);
    }

    /**
     * 判断{@code sue}是否满足要求。
     *
     * @param severity {@code severity}参数
     * @param code {@code code}参数
     * @param entityType 业务类型
     * @param sourceId 资源标识
     * @param summary {@code summary}参数
     */
    void issue(String severity, String code, String entityType, String sourceId, String summary) {
        issues.add(new Issue(severity, code, entityType, sourceId, summary));
    }

    /**
     * 处理{@code finish}相关逻辑。
     *
     * @param passed {@code passed}参数
     */
    void finish(boolean passed) {
        finishedAt = Instant.now();
        status = passed ? "passed" : "failed";
    }

    /**
     * 处理{@code passed}并返回对应结果。
     *
     * @return 判断结果，{@code true} 表示条件成立
     */
    boolean passed() {
        return "passed".equals(status)
            && canPass();
    }

    /**
     * 判断{@code Pass}是否满足要求。
     *
     * @return 判断结果，{@code true} 表示条件成立
     */
    boolean canPass() {
        return issues.stream().noneMatch(issue -> "error".equals(issue.severity()) || "fatal".equals(issue.severity()))
            && entities.stream().noneMatch(result -> "failed".equals(result.status()));
    }

    /**
     * 处理{@code payload}并返回对应结果。
     *
     * @return 处理结果
     */
    Map<String, Object> payload() {
        long sourceCount = entities.stream().mapToLong(EntityResult::sourceCount).sum();
        long targetCount = entities.stream().mapToLong(EntityResult::mappedCount).sum();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("sourceCount", sourceCount);
        summary.put("mappedCount", targetCount);
        summary.put("insertedCount", entities.stream().mapToLong(EntityResult::insertedCount).sum());
        summary.put("reusedCount", entities.stream().mapToLong(EntityResult::reusedCount).sum());
        summary.put("skippedCount", entities.stream().mapToLong(EntityResult::skippedCount).sum());
        summary.put("failedCount", entities.stream().mapToLong(EntityResult::failedCount).sum());
        summary.put("issueCount", issues.size());
        summary.put("errorCount", issues.stream().filter(issue -> SetHolder.ERRORS.contains(issue.severity())).count());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("formatVersion", 1);
        payload.put("targetHashAlgorithm", "canonical-row-v1");
        payload.put("command", command);
        payload.put("runKey", runKey);
        payload.put("runId", runId);
        payload.put("status", status);
        payload.put("startedAt", startedAt.toString());
        payload.put("finishedAt", finishedAt == null ? null : finishedAt.toString());
        payload.put("summary", summary);
        payload.put("entities", entities.stream().sorted(Comparator.comparing(EntityResult::entityType)).toList());
        payload.put("issues", List.copyOf(issues));
        payload.put("reportHash", json.sha256(Map.of(
            "runKey", runKey,
            "entities", entities,
            "issues", issues
        )));
        return Collections.unmodifiableMap(payload);
    }

    /**
     * 处理{@code write}并返回对应结果。
     *
     * @param directory 目录参数
     * @return 处理结果
     */
    ReportFiles write(Path directory) {
        try {
            Files.createDirectories(directory);
            String base = sanitize(runKey);
            Path jsonFile = directory.resolve(base + ".json");
            Path markdownFile = directory.resolve(base + ".md");
            Files.writeString(jsonFile, json.writePretty(payload()) + System.lineSeparator(), StandardCharsets.UTF_8);
            Files.writeString(markdownFile, markdown(), StandardCharsets.UTF_8);
            return new ReportFiles(jsonFile, markdownFile);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot write migration report", exception);
        }
    }

    /**
     * 处理{@code markdown}并返回对应结果。
     *
     * @return 处理结果
     */
    private String markdown() {
        StringBuilder output = new StringBuilder();
        output.append("# Nhs Migration Report\n\n");
        output.append("- Run key: `").append(runKey).append("`\n");
        output.append("- Run ID: `").append(runId == null ? "n/a" : runId).append("`\n");
        output.append("- Command: `").append(command).append("`\n");
        output.append("- Status: `").append(status).append("`\n");
        output.append("- Started: `").append(startedAt).append("`\n");
        output.append("- Finished: `").append(finishedAt == null ? "running" : finishedAt).append("`\n\n");
        output.append("| Entity | Status | Source | Mapped | Inserted | Reused | Skipped | Failed |\n");
        output.append("|---|---:|---:|---:|---:|---:|---:|---:|\n");
        entities.stream().sorted(Comparator.comparing(EntityResult::entityType)).forEach(result -> output
            .append('|').append(result.entityType())
            .append('|').append(result.status())
            .append('|').append(result.sourceCount())
            .append('|').append(result.mappedCount())
            .append('|').append(result.insertedCount())
            .append('|').append(result.reusedCount())
            .append('|').append(result.skippedCount())
            .append('|').append(result.failedCount()).append("|\n"));
        output.append("\n## Issues\n\n");
        if (issues.isEmpty()) {
            output.append("No issues.\n");
        } else {
            issues.forEach(issue -> output.append("- `").append(issue.severity()).append("` `")
                .append(issue.code()).append("` ")
                .append(issue.entityType() == null ? "" : issue.entityType() + " ")
                .append(issue.sourceId() == null ? "" : issue.sourceId() + ": ")
                .append(issue.summary().replace('\n', ' ')).append('\n'));
        }
        return output.toString();
    }

    /**
     * 处理{@code sanitize}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String sanitize(String value) {
        String result = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return result.length() <= 120 ? result : result.substring(0, 120);
    }

    /**
     * 封装{@code Entity}相关的不可变数据。
     */
    record EntityResult(
        String entityType,
        String phase,
        String status,
        long sourceCount,
        long mappedCount,
        long insertedCount,
        long reusedCount,
        long skippedCount,
        long failedCount,
        String sourceHash,
        String targetHash,
        Map<String, Object> detail
    ) {
        /**
         * 处理{@code inventory}并返回对应结果。
         *
         * @param entityType 业务类型
         * @param status 目标状态
         * @param count {@code count}参数
         * @param hash {@code hash}参数
         * @param detail {@code detail}参数
         * @return 处理结果
         */
        static EntityResult inventory(
            String entityType,
            String status,
            long count,
            String hash,
            Map<String, Object> detail
        ) {
            return new EntityResult(entityType, "inventory", status, count, 0, 0, 0, 0, 0, hash, null, detail);
        }
    }

    /**
     * 封装{@code Issue}相关的不可变数据。
     */
    record Issue(String severity, String code, String entityType, String sourceId, String summary) {
    }

    /**
     * 封装报表Files相关的不可变数据。
     */
    record ReportFiles(Path json, Path markdown) {
    }

    /**
     * 表示{@code SetHolder}相关的领域对象。
     */
    private static final class SetHolder {
        private static final java.util.Set<String> ERRORS = java.util.Set.of("error", "fatal");
    }
}
