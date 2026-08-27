package group.aitools.nhs.platform.nhs.portal.memory;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import group.aitools.nhs.platform.memory.service.MemoryVectorApplicationService;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 提供门户记忆相关的 HTTP 接口，并负责请求校验与结果返回。
 * Nhs personal and governed memory-management routes. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping("/api/portal/memory")
public class PortalMemoryController {

    private final PortalMemoryService service;

    public PortalMemoryController(PortalMemoryService service) {
        this.service = service;
    }

    /**
     * 处理{@code myCapabilities}并返回对应结果。
     *
     * @return 处理结果
     */
    @GetMapping("/my/capabilities")
    public R<Map<String, Object>> myCapabilities() {
        return R.ok(service.capabilities());
    }

    /**
     * 处理{@code mySummaries}并返回对应结果。
     *
     * @param keyword {@code keyword}参数
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/my/summaries")
    public R<List<Map<String, Object>>> mySummaries(
        @RequestParam(required = false) String keyword,
        @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit
    ) {
        return R.ok(service.summaries(keyword, limit));
    }

    /**
     * 处理{@code mySummaryDetail}并返回对应结果。
     *
     * @param conversationId 资源标识
     * @param historyLimit 数量上限
     * @return 处理结果
     */
    @GetMapping("/my/summaries/{conversationId}")
    public R<Map<String, Object>> mySummaryDetail(
        @PathVariable String conversationId,
        @RequestParam(defaultValue = "30") @Min(1) @Max(100) int historyLimit
    ) {
        return R.ok(service.summaryDetail(conversationId, historyLimit));
    }

    /**
     * 删除{@code MySummary}。
     *
     * @param conversationId 资源标识
     * @return 处理结果
     */
    @DeleteMapping("/my/summaries/{conversationId}")
    public R<Void> deleteMySummary(@PathVariable String conversationId) {
        service.deleteSummary(conversationId);
        return R.ok("已清除该会话的记忆摘要");
    }

    /**
     * 清理或重置My记忆。
     *
     * @return 处理结果
     */
    @DeleteMapping("/my/session-memory")
    public R<Map<String, Object>> clearMyMemory() {
        Map<String, Object> result = service.clearSessionMemory();
        result.put("message", "已清除全部会话摘要（会话正文按平台留痕策略保留）");
        return R.ok(result);
    }

    /**
     * 处理{@code myLtm}并返回对应结果。
     *
     * @return 处理结果
     */
    @GetMapping("/my/ltm")
    public R<Map<String, String>> myLtm() {
        return R.ok(service.ltm());
    }

    /**
     * 处理{@code putLtm}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PutMapping("/my/ltm")
    public R<Void> putLtm(@Valid @RequestBody LtmRequest request) {
        service.putLtm(request.key(), request.value());
        return R.ok("偏好记忆已更新");
    }

    /**
     * 删除{@code Ltm}。
     *
     * @param key {@code key}参数
     * @return 处理结果
     */
    @DeleteMapping("/my/ltm/{key}")
    public R<Void> deleteLtm(@PathVariable String key) {
        service.deleteLtm(key);
        return R.ok("偏好记忆已删除");
    }

    /**
     * 处理{@code myDaily}并返回对应结果。
     *
     * @param keyword {@code keyword}参数
     * @param dateFrom {@code dateFrom}参数
     * @param dateTo {@code dateTo}参数
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/my/daily-summaries")
    public R<List<Map<String, Object>>> myDaily(
        @RequestParam(required = false) String keyword,
        @RequestParam(name = "date_from", required = false) String dateFrom,
        @RequestParam(name = "date_to", required = false) String dateTo,
        @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit
    ) {
        return R.ok(service.dailySummaries(keyword, dateFrom, dateTo, limit));
    }

    /**
     * 处理{@code myDailyDetail}并返回对应结果。
     *
     * @param day {@code day}参数
     * @return 处理结果
     */
    @GetMapping("/my/daily-summaries/{day}")
    public R<Map<String, Object>> myDailyDetail(@PathVariable String day) {
        return R.ok(service.dailyDetail(day));
    }

    /**
     * 删除{@code MyDaily}。
     *
     * @param day {@code day}参数
     * @return 处理结果
     */
    @DeleteMapping("/my/daily-summaries/{day}")
    public R<Void> deleteMyDaily(@PathVariable String day) {
        service.deleteDaily(day);
        return R.ok("已删除每日摘要");
    }

    /**
     * 处理{@code rebuildMyDaily}并返回对应结果。
     *
     * @param day {@code day}参数
     * @return 处理结果
     */
    @PostMapping("/my/daily-summaries/{day}/rebuild")
    public R<Map<String, Object>> rebuildMyDaily(@PathVariable String day) {
        return R.ok(service.rebuildDaily(day));
    }

    /**
     * 处理{@code consolidateMy}并返回对应结果。
     *
     * @return 处理结果
     */
    @PostMapping("/my/consolidate")
    public R<Map<String, Object>> consolidateMy() {
        return R.ok(service.consolidate());
    }

    /**
     * 处理{@code consolidate}并返回对应结果。
     *
     * @return 处理结果
     */
    @PostMapping("/consolidate")
    public R<Map<String, Object>> consolidate() {
        return R.ok(service.consolidate());
    }

    /**
     * 处理{@code summaries}并返回对应结果。
     *
     * @param userId 资源标识
     * @param conversationId 资源标识
     * @param keyword {@code keyword}参数
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/summaries")
    public R<List<Map<String, Object>>> summaries(
        @RequestParam(name = "user_id", required = false) Long userId,
        @RequestParam(name = "conversation_id", required = false) String conversationId,
        @RequestParam(required = false) String keyword,
        @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit
    ) {
        Long target = userId == null ? service.currentUserId() : userId;
        var values = service.summariesForUser(target, keyword, limit).stream()
            .filter(value -> conversationId == null || conversationId.equals(String.valueOf(value.get("conversation_id"))))
            .toList();
        return R.ok(values);
    }

    /**
     * 处理{@code dailySummaries}并返回对应结果。
     *
     * @param userId 资源标识
     * @param keyword {@code keyword}参数
     * @param dateFrom {@code dateFrom}参数
     * @param dateTo {@code dateTo}参数
     * @param limit 数量上限
     * @return 处理结果
     */
    @GetMapping("/daily-summaries")
    public R<List<Map<String, Object>>> dailySummaries(
        @RequestParam(name = "user_id", required = false) Long userId,
        @RequestParam(required = false) String keyword,
        @RequestParam(name = "date_from", required = false) String dateFrom,
        @RequestParam(name = "date_to", required = false) String dateTo,
        @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit
    ) {
        return R.ok(service.dailySummariesForUser(
            userId == null ? service.currentUserId() : userId, keyword, dateFrom, dateTo, limit
        ));
    }

    /**
     * 处理{@code summaryDetail}并返回对应结果。
     *
     * @param targetUserId 资源标识
     * @param conversationId 资源标识
     * @param historyLimit 数量上限
     * @return 处理结果
     */
    @GetMapping("/summaries/{targetUserId}/{conversationId}")
    public R<Map<String, Object>> summaryDetail(
        @PathVariable Long targetUserId,
        @PathVariable String conversationId,
        @RequestParam(defaultValue = "30") @Min(1) @Max(100) int historyLimit
    ) {
        return R.ok(service.summaryDetailForUser(targetUserId, conversationId, historyLimit));
    }

    /**
     * 删除{@code Summary}。
     *
     * @param targetUserId 资源标识
     * @param conversationId 资源标识
     * @return 处理结果
     */
    @DeleteMapping("/summaries/{targetUserId}/{conversationId}")
    public R<Void> deleteSummary(
        @PathVariable Long targetUserId,
        @PathVariable String conversationId
    ) {
        service.deleteSummaryForUser(targetUserId, conversationId);
        return R.ok("已删除摘要");
    }

    /**
     * 删除用户记忆。
     *
     * @param targetUserId 资源标识
     * @return 处理结果
     */
    @DeleteMapping("/users/{targetUserId}")
    public R<Map<String, Object>> deleteUserMemory(@PathVariable Long targetUserId) {
        Map<String, Object> result = service.clearSessionMemoryForUser(targetUserId);
        result.put("message", "已清空用户会话摘要和每日摘要");
        return R.ok(result);
    }

    /**
     * 处理{@code dailyDetail}并返回对应结果。
     *
     * @param targetUserId 资源标识
     * @param day {@code day}参数
     * @return 处理结果
     */
    @GetMapping("/daily-summaries/{targetUserId}/{day}")
    public R<Map<String, Object>> dailyDetail(
        @PathVariable Long targetUserId,
        @PathVariable String day
    ) {
        return R.ok(service.dailyDetailForUser(targetUserId, day));
    }

    /**
     * 删除{@code Daily}。
     *
     * @param targetUserId 资源标识
     * @param day {@code day}参数
     * @return 处理结果
     */
    @DeleteMapping("/daily-summaries/{targetUserId}/{day}")
    public R<Void> deleteDaily(
        @PathVariable Long targetUserId,
        @PathVariable String day
    ) {
        service.deleteDailyForUser(targetUserId, day);
        return R.ok("已删除每日摘要");
    }

    /**
     * 处理{@code rebuildDaily}并返回对应结果。
     *
     * @param targetUserId 资源标识
     * @param day {@code day}参数
     * @return 处理结果
     */
    @PostMapping("/daily-summaries/{targetUserId}/{day}/rebuild")
    public R<Map<String, Object>> rebuildDaily(
        @PathVariable Long targetUserId,
        @PathVariable String day
    ) {
        return R.ok(service.rebuildDailyForUser(targetUserId, day));
    }

    /**
     * 查询{@code Test}列表。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/search-test")
    public R<List<Map<String, Object>>> searchTest(@Valid @RequestBody SearchTestRequest request) {
        return R.ok(service.search(request.query(), request.limit()));
    }

    /**
     * 处理{@code config}并返回对应结果。
     *
     * @return 处理结果
     */
    @GetMapping("/configs")
    public R<Map<String, Object>> config() {
        return R.ok(service.config());
    }

    /**
     * 更新{@code Config}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PutMapping("/configs")
    public R<Map<String, Object>> updateConfig(@Valid @RequestBody ConfigRequest request) {
        Integer topK = request.search_knn_top_k() == null
            ? request.default_search_limit() : request.search_knn_top_k();
        return R.ok(service.updateConfig(new MemoryVectorApplicationService.SettingsPatch(
            request.enabled(), request.summary_enabled(), request.embedding_enabled(),
            request.embedding_model_id(), request.embedding_dimension(), topK,
            request.vector_weight(), request.consolidation_threshold(),
            request.base_half_life_days(), request.summary_ttl_days(), request.expected_revision()
        )));
    }

    /**
     * 处理{@code indexStatus}并返回对应结果。
     *
     * @return 处理结果
     */
    @GetMapping("/index/status")
    public R<Map<String, Object>> indexStatus() {
        return R.ok(service.indexStatus());
    }

    /**
     * 处理{@code rebuildIndex}并返回对应结果。
     *
     * @return 处理结果
     */
    @PostMapping("/index/rebuild")
    public R<Map<String, Object>> rebuildIndex() {
        return R.ok(service.rebuildVectorIndex());
    }

    /**
     * 处理{@code redisVectorTest}并返回对应结果。
     *
     * @return 处理结果
     */
    @GetMapping("/redis-vector-test")
    public R<Map<String, Object>> redisVectorTest() {
        return R.ok(service.testVectorStore());
    }

    /**
     * 处理{@code testEmbedding}并返回对应结果。
     *
     * @return 处理结果
     */
    @PostMapping("/test-embedding")
    public R<Map<String, Object>> testEmbedding() {
        return R.ok(service.testEmbedding());
    }

    /**
     * 封装{@code Ltm}相关的不可变数据。
     */
    public record LtmRequest(
        @NotBlank @Size(max = 128) String key,
        @NotBlank @Size(max = 4000) String value
    ) {
    }

    /**
     * 封装{@code SearchTest}相关的不可变数据。
     */
    public record SearchTestRequest(
        @NotBlank @Size(max = 255) String query,
        @Min(1) @Max(200) Integer limit
    ) {
    }

    /**
     * 封装{@code Config}相关的不可变数据。
     */
    public record ConfigRequest(
        Boolean enabled,
        Boolean summary_enabled,
        Boolean embedding_enabled,
        @Min(1) Long embedding_model_id,
        @Min(1) @Max(8192) Integer embedding_dimension,
        @Min(1) @Max(200) Integer search_knn_top_k,
        @Min(1) @Max(200) Integer default_search_limit,
        @DecimalMin("0.0") @DecimalMax("1.0") Double vector_weight,
        @DecimalMin("0.0") @DecimalMax("1.0") Double consolidation_threshold,
        @DecimalMin("0.01") @DecimalMax("3650.0") Double base_half_life_days,
        @Min(1) @Max(3650) Integer summary_ttl_days,
        @Min(1) Long expected_revision
    ) {
    }
}
