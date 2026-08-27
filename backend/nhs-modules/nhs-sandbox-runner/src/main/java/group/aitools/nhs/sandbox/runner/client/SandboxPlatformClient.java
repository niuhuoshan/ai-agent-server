package group.aitools.nhs.sandbox.runner.client;

import group.aitools.nhs.sandbox.runner.client.SandboxProtocol.ClaimedJob;
import group.aitools.nhs.sandbox.runner.client.SandboxProtocol.Completion;
import group.aitools.nhs.sandbox.runner.client.SandboxProtocol.Heartbeat;
import group.aitools.nhs.sandbox.runner.client.SandboxProtocol.OutputChunk;
import group.aitools.nhs.sandbox.runner.client.SandboxProtocol.Registration;
import group.aitools.nhs.sandbox.runner.client.SandboxProtocol.RegistrationRequest;
import group.aitools.nhs.sandbox.runner.config.SandboxRunnerProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 表示沙箱平台相关的领域对象。
 */
@Component
public class SandboxPlatformClient {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");

    private final SandboxRunnerProperties properties;
    private final RunnerCredentialStore credentialStore;
    private final JsonMapper jsonMapper;
    private final HttpClient httpClient;
    private final URI baseUri;
    private volatile String runnerSecret;

    /**
     * 创建 {@code SandboxPlatformClient} 实例并初始化所需依赖。
     *
     * @param properties {@code properties}参数
     * @param credentialStore 凭据Store参数
     * @param jsonMapper {@code jsonMapper}参数
     */
    public SandboxPlatformClient(
        SandboxRunnerProperties properties,
        RunnerCredentialStore credentialStore,
        JsonMapper jsonMapper
    ) {
        this.properties = properties;
        this.credentialStore = credentialStore;
        this.jsonMapper = jsonMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        this.baseUri = validateBaseUri(properties.getPlatformBaseUrl());
    }

    /**
     * 校验{@code Registered}，并在条件不满足时终止处理。
     */
    public synchronized void ensureRegistered() {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (runnerSecret != null) {
            return;
        }
        runnerSecret = credentialStore.read().orElse(null);
        if (runnerSecret != null) {
            return;
        }
        String bootstrap = properties.getBootstrapToken();
        if (bootstrap == null || bootstrap.strip().length() < 32) {
            throw new IllegalStateException(
                "Runner has no credential and NHS_SANDBOX_BOOTSTRAP_TOKEN is unavailable"
            );
        }
        RegistrationRequest request = new RegistrationRequest(
            properties.getRunnerKey(), properties.getRunnerName(), capabilities(),
            properties.getMaxConcurrency(), properties.getRunnerVersion()
        );
        Registration registration = post(
            "/internal/sandbox/v1/runners/register", bootstrap.strip(), false,
            request, Registration.class, null
        );
        if (registration == null || registration.runnerSecret() == null) {
            throw new IllegalStateException("Platform registration returned no Runner secret");
        }
        credentialStore.write(registration.runnerSecret());
        runnerSecret = registration.runnerSecret();
    }

    /**
     * 处理{@code heartbeat}相关逻辑。
     *
     * @param activeJobCount active作业Count参数
     */
    public void heartbeat(int activeJobCount) {
        ensureRegistered();
        post(
            "/internal/sandbox/v1/runners/heartbeat", runnerSecret, true,
            new Heartbeat(
                capabilities(), properties.getMaxConcurrency(), activeJobCount,
                properties.getRunnerVersion()
            ), Void.class, null
        );
    }

    /**
     * 处理{@code claim}并返回对应结果。
     *
     * @return 处理结果
     */
    public ClaimedJob claim() {
        ensureRegistered();
        return post(
            "/internal/sandbox/v1/jobs/claim", runnerSecret, true,
            null, ClaimedJob.class, null
        );
    }

    /**
     * 处理{@code start}相关逻辑。
     *
     * @param job 作业参数
     */
    public void start(ClaimedJob job) {
        post(
            "/internal/sandbox/v1/jobs/" + job.jobId() + "/start", runnerSecret, true,
            null, Void.class, job.jobToken()
        );
    }

    /**
     * 处理{@code renew}相关逻辑。
     *
     * @param job 作业参数
     */
    public void renew(ClaimedJob job) {
        post(
            "/internal/sandbox/v1/jobs/" + job.jobId() + "/renew", runnerSecret, true,
            null, Void.class, job.jobToken()
        );
    }

    /**
     * 处理{@code appendOutput}相关逻辑。
     *
     * @param job 作业参数
     * @param output {@code output}参数
     */
    public void appendOutput(ClaimedJob job, OutputChunk output) {
        post(
            "/internal/sandbox/v1/jobs/" + job.jobId() + "/output", runnerSecret, true,
            output, Void.class, job.jobToken()
        );
    }

    /**
     * 处理{@code complete}相关逻辑。
     *
     * @param job 作业参数
     * @param completion {@code completion}参数
     */
    public void complete(ClaimedJob job, Completion completion) {
        post(
            "/internal/sandbox/v1/jobs/" + job.jobId() + "/complete", runnerSecret, true,
            completion, Void.class, job.jobToken()
        );
    }

    /**
 * 处理download技能Bundle并返回对应结果。
 *
     * Downloads the immutable Skill bundle for one claimed job.  The endpoint deliberately
     * returns raw tar.gz bytes rather than a JSON envelope so binary Skill files survive the
     * control-plane hop without a lossy text conversion.
     */
    public SkillBundle downloadSkillBundle(ClaimedJob job) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (job == null || job.jobId() == null || job.jobToken() == null) {
            throw new IllegalArgumentException("Skill bundle download requires a claimed job");
        }
        String expectedManifestHash = normalizeManifestHash(
            job.skillManifestHash(), "Claimed job Skill manifest hash is invalid",
            IllegalArgumentException::new
        );
        ensureRegistered();
        try {
            HttpRequest request = HttpRequest.newBuilder(
                    baseUri.resolve("/internal/sandbox/v1/jobs/" + job.jobId() + "/skill-bundle")
                )
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + runnerSecret)
                .header("X-Sandbox-Runner-Key", properties.getRunnerKey())
                .header("X-Sandbox-Job-Token", job.jobToken())
                .header("X-Sandbox-Timestamp", Long.toString(Instant.now().getEpochSecond()))
                .header("X-Sandbox-Nonce", nonce())
                .GET()
                .build();
            HttpResponse<byte[]> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofByteArray()
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(
                    "Skill bundle download failed with HTTP " + response.statusCode()
                );
            }
            String manifestHash = response.headers()
                .firstValue("X-Sandbox-Skill-Manifest-Hash")
                .orElse(null);
            if (manifestHash == null || manifestHash.isBlank()) {
                throw new IllegalStateException("Skill bundle response omitted manifest hash");
            }
            String normalizedManifestHash = normalizeManifestHash(
                manifestHash, "Skill bundle response manifest hash is invalid",
                IllegalStateException::new
            );
            if (!expectedManifestHash.equals(normalizedManifestHash)) {
                throw new IllegalStateException(
                    "Skill bundle response manifest hash does not match the claimed job"
                );
            }
            return new SkillBundle(response.body(), normalizedManifestHash);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot download sandbox Skill bundle", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Skill bundle download interrupted", exception);
        }
    }

    /**
     * 处理{@code post}并返回对应结果。
     *
     * @param path {@code path}参数
     * @param credential 凭据参数
     * @param runnerRequest 请求参数
     * @param body {@code body}参数
     * @param responseType 业务类型
     * @param jobToken 作业令牌参数
     * @return 处理结果
     */
    private <T> T post(
        String path,
        String credential,
        boolean runnerRequest,
        Object body,
        Class<T> responseType,
        String jobToken
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + credential)
                .header("X-Sandbox-Timestamp", Long.toString(Instant.now().getEpochSecond()))
                .header("X-Sandbox-Nonce", nonce())
                .header("Content-Type", "application/json");
            if (runnerRequest) {
                request.header("X-Sandbox-Runner-Key", properties.getRunnerKey());
            }
            if (jobToken != null) {
                request.header("X-Sandbox-Job-Token", jobToken);
            }
            String jsonBody = body == null ? "{}" : jsonMapper.writeValueAsString(body);
            HttpResponse<byte[]> response = httpClient.send(
                request.POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofByteArray()
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Platform request failed with HTTP " + response.statusCode());
            }
            JsonNode root = jsonMapper.readTree(response.body());
            int code = root == null || root.get("code") == null ? -1 : root.get("code").asInt();
            if (code != 200) {
                String message = root == null || root.get("msg") == null
                    ? "unknown platform error" : root.get("msg").asText();
                throw new IllegalStateException("Platform rejected Runner request: " + message);
            }
            JsonNode data = root.get("data");
            if (responseType == Void.class || data == null || data.isNull()) {
                return null;
            }
            return jsonMapper.treeToValue(data, responseType);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot call sandbox platform API", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Sandbox platform API call interrupted", exception);
        }
    }

    /**
     * 处理{@code capabilities}并返回对应结果。
     *
     * @return 符合条件的数据集合
     */
    private Set<String> capabilities() {
        return Set.copyOf(properties.getTemplates().keySet());
    }

    /**
     * 处理{@code normalizeManifestHash}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param message 待处理内容
     * @param exceptionFactory {@code exceptionFactory}参数
     * @return 处理结果
     */
    private <T extends RuntimeException> String normalizeManifestHash(
        String value,
        String message,
        java.util.function.Function<String, T> exceptionFactory
    ) {
        String normalized = value == null ? "" : value.strip();
        if (!SHA256.matcher(normalized).matches()) {
            throw exceptionFactory.apply(message);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    /**
     * 封装技能Bundle相关的不可变数据。
     */
    public record SkillBundle(byte[] bytes, String manifestHash) {
        /**
         * 创建 {@code SkillBundle} 实例并初始化所需依赖。
         *
         * @param bytes {@code bytes}参数
         * @param manifestHash {@code manifestHash}参数
         */
        public SkillBundle {
            if (bytes == null || bytes.length == 0) {
                throw new IllegalArgumentException("Skill bundle is empty");
            }
            bytes = bytes.clone();
            if (manifestHash == null || manifestHash.isBlank()) {
                throw new IllegalArgumentException("Skill bundle manifest hash is missing");
            }
            manifestHash = manifestHash.strip().toLowerCase(Locale.ROOT);
            if (!SHA256.matcher(manifestHash).matches()) {
                throw new IllegalArgumentException("Skill bundle manifest hash is invalid");
            }
        }

        /**
         * 处理{@code bytes}并返回对应结果。
         *
         * @return 处理结果
         */
        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    /**
     * 校验{@code BaseUri}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private URI validateBaseUri(String value) {
        URI uri = URI.create(value == null ? "" : value.strip());
        if (!uri.isAbsolute()
            || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
            || uri.getUserInfo() != null
            || uri.getFragment() != null
            || uri.getQuery() != null) {
            throw new IllegalArgumentException("Sandbox platform base URL is invalid");
        }
        String normalized = uri.toString();
        return URI.create(normalized.endsWith("/") ? normalized : normalized + "/");
    }

    /**
     * 处理{@code nonce}并返回对应结果。
     *
     * @return 处理结果
     */
    private String nonce() {
        byte[] bytes = new byte[24];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
