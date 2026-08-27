package group.aitools.nhs.platform.operations.web;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import group.aitools.nhs.platform.operations.service.RedisOperationsApplicationService;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供{@code RedisOperations}相关的 HTTP 接口，并负责请求校验与结果返回。
 * Nhs-compatible Redis browser and guarded maintenance endpoints. */
@Validated
@SaCheckLogin
@RestController
@RequestMapping({"/platform/operations/redis", "/api/portal/system/redis"})
public class RedisOperationsController {

    private final RedisOperationsApplicationService service;

    public RedisOperationsController(RedisOperationsApplicationService service) {
        this.service = service;
    }

    /**
     * 查询{@code list}列表。
     *
     * @param pattern {@code pattern}参数
     * @return 处理结果
     */
    @GetMapping({"/keys", "/keys-list"})
    public R<RedisKeyListView> list(
        @RequestParam(required = false, defaultValue = "*") @Size(max = 128) String pattern
    ) {
        return R.ok(service.list(pattern));
    }

    /**
 * 查询{@code Post}列表。
 * Legacy Nhs clients submit the scan request as POST. */
    @PostMapping("/keys")
    public R<RedisKeyListView> listPost() {
        return R.ok(service.list("*"));
    }

    /**
     * 处理{@code detail}并返回对应结果。
     *
     * @param key {@code key}参数
     * @return 处理结果
     */
    @GetMapping("/key-detail")
    public R<RedisKeyDetailView> detail(@RequestParam String key) {
        return R.ok(service.detail(key));
    }

    /**
     * 删除{@code delete}。
     *
     * @param key {@code key}参数
     * @param confirm {@code confirm}参数
     * @return 处理结果
     */
    @DeleteMapping("/key")
    public R<RedisMutationView> delete(
        @RequestParam String key,
        @RequestParam(defaultValue = "false") boolean confirm
    ) {
        return R.ok(service.delete(key, confirm));
    }

    /**
     * 删除{@code Batch}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/delete-keys")
    public R<RedisMutationView> deleteBatch(@Valid @RequestBody RedisDeleteRequest request) {
        return R.ok(service.deleteBatch(request));
    }

    /**
     * 处理{@code flush}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/flush")
    public R<RedisMutationView> flush(@Valid @RequestBody RedisFlushRequest request) {
        return R.ok(service.flush(request));
    }
}
