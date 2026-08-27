package group.aitools.nhs.platform.sandbox.web;

import group.aitools.nhs.platform.sandbox.service.SandboxRequestAuthenticator;
import group.aitools.nhs.platform.sandbox.service.SandboxRequestAuthenticator.RunnerAuthentication;
import group.aitools.nhs.platform.sandbox.service.SandboxRunnerApplicationService;
import group.aitools.nhs.common.core.domain.R;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供沙箱Runner相关的 HTTP 接口，并负责请求校验与结果返回。
 */
@RestController
@RequestMapping("/internal/sandbox/v1")
public class SandboxRunnerController {

    private static final String RUNNER_KEY = "X-Sandbox-Runner-Key";
    private static final String TIMESTAMP = "X-Sandbox-Timestamp";
    private static final String NONCE = "X-Sandbox-Nonce";
    private static final String JOB_TOKEN = "X-Sandbox-Job-Token";

    private final SandboxRequestAuthenticator authenticator;
    private final SandboxRunnerApplicationService service;

    /**
     * 创建 {@code SandboxRunnerController} 实例并初始化所需依赖。
     *
     * @param authenticator {@code authenticator}参数
     * @param service {@code service}参数
     */
    public SandboxRunnerController(
        SandboxRequestAuthenticator authenticator,
        SandboxRunnerApplicationService service
    ) {
        this.authenticator = authenticator;
        this.service = service;
    }

    /**
     * 创建并保存{@code register}。
     *
     * @param authorization 授权参数
     * @param timestamp {@code timestamp}参数
     * @param nonce {@code nonce}参数
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/runners/register")
    public R<SandboxRunnerRegistrationView> register(
        @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
        @RequestHeader(TIMESTAMP) String timestamp,
        @RequestHeader(NONCE) String nonce,
        @RequestBody RegisterSandboxRunnerRequest request
    ) {
        authenticator.authenticateRegistration(authorization, timestamp, nonce);
        return R.ok(service.register(request));
    }

    /**
     * 处理{@code heartbeat}并返回对应结果。
     *
     * @param runnerKey {@code runnerKey}参数
     * @param authorization 授权参数
     * @param timestamp {@code timestamp}参数
     * @param nonce {@code nonce}参数
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/runners/heartbeat")
    public R<Void> heartbeat(
        @RequestHeader(RUNNER_KEY) String runnerKey,
        @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
        @RequestHeader(TIMESTAMP) String timestamp,
        @RequestHeader(NONCE) String nonce,
        @RequestBody SandboxRunnerHeartbeatRequest request
    ) {
        RunnerAuthentication runner = authenticator.authenticateRunner(
            runnerKey, authorization, timestamp, nonce
        );
        service.heartbeat(runner, request);
        return R.ok();
    }

    /**
     * 处理{@code claim}并返回对应结果。
     *
     * @param runnerKey {@code runnerKey}参数
     * @param authorization 授权参数
     * @param timestamp {@code timestamp}参数
     * @param nonce {@code nonce}参数
     * @return 处理结果
     */
    @PostMapping("/jobs/claim")
    public R<SandboxJobClaimView> claim(
        @RequestHeader(RUNNER_KEY) String runnerKey,
        @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
        @RequestHeader(TIMESTAMP) String timestamp,
        @RequestHeader(NONCE) String nonce
    ) {
        RunnerAuthentication runner = authenticator.authenticateRunner(
            runnerKey, authorization, timestamp, nonce
        );
        return R.ok(service.claim(runner));
    }

    /**
     * 处理{@code start}并返回对应结果。
     *
     * @param jobId 资源标识
     * @param runnerKey {@code runnerKey}参数
     * @param authorization 授权参数
     * @param timestamp {@code timestamp}参数
     * @param nonce {@code nonce}参数
     * @param jobToken 作业令牌参数
     * @return 处理结果
     */
    @PostMapping("/jobs/{jobId}/start")
    public R<Void> start(
        @PathVariable Long jobId,
        @RequestHeader(RUNNER_KEY) String runnerKey,
        @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
        @RequestHeader(TIMESTAMP) String timestamp,
        @RequestHeader(NONCE) String nonce,
        @RequestHeader(JOB_TOKEN) String jobToken
    ) {
        RunnerAuthentication runner = authenticator.authenticateRunner(
            runnerKey, authorization, timestamp, nonce
        );
        service.start(runner, jobId, jobToken);
        return R.ok();
    }

    /**
     * 处理{@code renew}并返回对应结果。
     *
     * @param jobId 资源标识
     * @param runnerKey {@code runnerKey}参数
     * @param authorization 授权参数
     * @param timestamp {@code timestamp}参数
     * @param nonce {@code nonce}参数
     * @param jobToken 作业令牌参数
     * @return 处理结果
     */
    @PostMapping("/jobs/{jobId}/renew")
    public R<Void> renew(
        @PathVariable Long jobId,
        @RequestHeader(RUNNER_KEY) String runnerKey,
        @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
        @RequestHeader(TIMESTAMP) String timestamp,
        @RequestHeader(NONCE) String nonce,
        @RequestHeader(JOB_TOKEN) String jobToken
    ) {
        RunnerAuthentication runner = authenticator.authenticateRunner(
            runnerKey, authorization, timestamp, nonce
        );
        service.renew(runner, jobId, jobToken);
        return R.ok();
    }

    /**
     * 处理技能Bundle并返回对应结果。
     *
     * @param jobId 资源标识
     * @param runnerKey {@code runnerKey}参数
     * @param authorization 授权参数
     * @param timestamp {@code timestamp}参数
     * @param nonce {@code nonce}参数
     * @param jobToken 作业令牌参数
     * @return 处理结果
     */
    @GetMapping(value = "/jobs/{jobId}/skill-bundle", produces = "application/gzip")
    public ResponseEntity<StreamingResponseBody> skillBundle(
        @PathVariable Long jobId,
        @RequestHeader(RUNNER_KEY) String runnerKey,
        @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
        @RequestHeader(TIMESTAMP) String timestamp,
        @RequestHeader(NONCE) String nonce,
        @RequestHeader(JOB_TOKEN) String jobToken
    ) {
        RunnerAuthentication runner = authenticator.authenticateRunner(
            runnerKey, authorization, timestamp, nonce
        );
        String manifestHash = service.skillBundleHash(runner, jobId, jobToken);
        StreamingResponseBody body = output -> service.writeSkillBundle(
            runner, jobId, jobToken, output
        );
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/gzip"))
            .header("Content-Disposition", "attachment; filename=skill-bundle.tar.gz")
            .header("X-Sandbox-Skill-Manifest-Hash", manifestHash)
            .body(body);
    }

    /**
     * 处理{@code complete}并返回对应结果。
     *
     * @param jobId 资源标识
     * @param runnerKey {@code runnerKey}参数
     * @param authorization 授权参数
     * @param timestamp {@code timestamp}参数
     * @param nonce {@code nonce}参数
     * @param jobToken 作业令牌参数
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/jobs/{jobId}/complete")
    public R<Void> complete(
        @PathVariable Long jobId,
        @RequestHeader(RUNNER_KEY) String runnerKey,
        @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
        @RequestHeader(TIMESTAMP) String timestamp,
        @RequestHeader(NONCE) String nonce,
        @RequestHeader(JOB_TOKEN) String jobToken,
        @RequestBody CompleteSandboxJobRequest request
    ) {
        RunnerAuthentication runner = authenticator.authenticateRunner(
            runnerKey, authorization, timestamp, nonce
        );
        service.complete(runner, jobId, jobToken, request);
        return R.ok();
    }

    /**
     * 处理{@code appendOutput}并返回对应结果。
     *
     * @param jobId 资源标识
     * @param runnerKey {@code runnerKey}参数
     * @param authorization 授权参数
     * @param timestamp {@code timestamp}参数
     * @param nonce {@code nonce}参数
     * @param jobToken 作业令牌参数
     * @param request 请求参数
     * @return 处理结果
     */
    @PostMapping("/jobs/{jobId}/output")
    public R<Void> appendOutput(
        @PathVariable Long jobId,
        @RequestHeader(RUNNER_KEY) String runnerKey,
        @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
        @RequestHeader(TIMESTAMP) String timestamp,
        @RequestHeader(NONCE) String nonce,
        @RequestHeader(JOB_TOKEN) String jobToken,
        @RequestBody AppendSandboxJobOutputRequest request
    ) {
        RunnerAuthentication runner = authenticator.authenticateRunner(
            runnerKey, authorization, timestamp, nonce
        );
        service.appendOutput(runner, jobId, jobToken, request);
        return R.ok();
    }
}
