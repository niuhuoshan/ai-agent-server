package group.aitools.nhs.platform.model.service;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.model.domain.AgentModel;
import group.aitools.nhs.platform.model.mapper.AgentModelMapper;
import group.aitools.nhs.platform.model.web.CreateModelRequest;
import group.aitools.nhs.platform.model.web.DiscoverModelsRequest;
import group.aitools.nhs.platform.model.web.ModelConnectionView;
import group.aitools.nhs.platform.model.web.ModelOptionView;
import group.aitools.nhs.platform.model.web.ModelReferenceView;
import group.aitools.nhs.platform.model.web.ModelView;
import group.aitools.nhs.platform.model.web.TestModelConfigRequest;
import group.aitools.nhs.platform.model.web.UpdateModelRequest;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 负责模型相关的业务编排与领域规则处理。
 * Model-registry use cases with strict secret and outbound-network boundaries. */
@Service
public class ModelApplicationService {

    private static final int MAX_JSON_BYTES = 32 * 1024;
    private static final int MAX_API_KEY_LENGTH = 8192;
    private static final Pattern MODEL_KEY = Pattern.compile("[a-z][a-z0-9._-]{0,127}");
    private static final Set<String> MODEL_TYPES = Set.of("chat", "embedding", "multimodal", "rerank");
    private static final Set<String> STATUSES = Set.of("active", "disabled", "testing");

    private final CurrentPrincipalProvider principalProvider;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final PlatformIdGenerator idGenerator;
    private final AgentModelMapper modelMapper;
    private final ModelConfigurationValidator configurationValidator;
    private final ModelEndpointPolicy endpointPolicy;
    private final ModelCredentialResolver credentialResolver;
    private final ModelProviderClient providerClient;
    private final JsonMapper jsonMapper;

    public ModelApplicationService(
        CurrentPrincipalProvider principalProvider,
        AuthorizationEnforcer authorizationEnforcer,
        PlatformIdGenerator idGenerator,
        AgentModelMapper modelMapper,
        ModelConfigurationValidator configurationValidator,
        ModelEndpointPolicy endpointPolicy,
        ModelCredentialResolver credentialResolver,
        ModelProviderClient providerClient,
        JsonMapper jsonMapper
    ) {
        this.principalProvider = principalProvider;
        this.authorizationEnforcer = authorizationEnforcer;
        this.idGenerator = idGenerator;
        this.modelMapper = modelMapper;
        this.configurationValidator = configurationValidator;
        this.endpointPolicy = endpointPolicy;
        this.credentialResolver = credentialResolver;
        this.providerClient = providerClient;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 查询{@code list}列表。
     *
     * @param modelType 业务类型
     * @param providerType 业务类型
     * @param search {@code search}参数
     * @param includeInactive {@code includeInactive}参数
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<ModelView> list(
        String modelType,
        String providerType,
        String search,
        boolean includeInactive,
        int limit
    ) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, context(null, null, "list"));
        return modelMapper.selectModels(
                normalizeFilter(modelType),
                normalizeFilter(providerType),
                normalizeSearch(search),
                includeInactive,
                limit
            ).stream()
            .map(model -> ModelView.from(model, jsonMapper))
            .toList();
    }

    /**
     * 获取{@code get}。
     *
     * @param modelId 资源标识
     * @return 处理结果
     */
    public ModelView get(Long modelId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, context(modelId, null, "view"));
        return ModelView.from(requireModel(modelId), jsonMapper);
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ModelView create(CreateModelRequest request) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        String modelKey = request.modelKey() == null ? "" : request.modelKey().strip();
        if (!MODEL_KEY.matcher(modelKey).matches()) {
            throw new ServiceException("模型标识格式无效", HttpStatus.BAD_REQUEST);
        }
        authorizationEnforcer.requireAllowed(principal, context(null, modelKey, "create"));

        AgentModel model = new AgentModel();
        model.setId(idGenerator.nextId());
        model.setModelKey(modelKey);
        applyConfiguration(
            model,
            request.displayName(),
            request.providerType(),
            request.modelName(),
            request.modelType(),
            request.endpointUrl(),
            apiKeyValue(request.apiKey(), null),
            request.contextSize(),
            request.maxOutputTokens(),
            request.reasoningConfig(),
            request.capabilities(),
            request.status()
        );
        model.setCreateBy(principal.id());
        model.setCreateTime(LocalDateTime.now());
        model.setDelFlag("0");
        model.setExtraJson("{}");
        try {
            modelMapper.insertModel(model);
        } catch (DuplicateKeyException exception) {
            throw new ServiceException("模型标识已存在：" + modelKey, HttpStatus.CONFLICT);
        }
        return ModelView.from(model, jsonMapper);
    }

    /**
     * 更新{@code update}。
     *
     * @param modelId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ModelView update(Long modelId, UpdateModelRequest request) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, context(modelId, null, "update"));
        modelMapper.lockModel(modelId);
        AgentModel model = requireModel(modelId);
        applyConfiguration(
            model,
            request.displayName(),
            request.providerType(),
            request.modelName(),
            request.modelType(),
            request.endpointUrl(),
            apiKeyValue(request.apiKey(), model.getCredentialRef()),
            request.contextSize(),
            request.maxOutputTokens(),
            request.reasoningConfig(),
            request.capabilities(),
            request.status()
        );
        model.setUpdateBy(principal.id());
        model.setUpdateTime(LocalDateTime.now());
        if (modelMapper.updateModel(model) != 1) {
            throw new ServiceException("模型不存在或已被删除", HttpStatus.NOT_FOUND);
        }
        return ModelView.from(model, jsonMapper);
    }

    /**
     * 处理{@code references}并返回对应结果。
     *
     * @param modelId 资源标识
     * @return 符合条件的数据集合
     */
    public List<ModelReferenceView> references(Long modelId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, context(modelId, null, "view"));
        requireModel(modelId);
        return modelMapper.selectActiveReferences(modelId).stream()
            .map(ModelReferenceView::from)
            .toList();
    }

    /**
     * 删除{@code delete}。
     *
     * @param modelId 资源标识
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long modelId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, context(modelId, null, "delete"));
        modelMapper.lockModel(modelId);
        requireModel(modelId);
        List<ModelReferenceView> references = modelMapper.selectActiveReferences(modelId).stream()
            .map(ModelReferenceView::from)
            .toList();
        if (!references.isEmpty()) {
            throw new ServiceException(
                "模型仍被 " + references.size() + " 个草稿或已发布 Agent 版本引用",
                HttpStatus.CONFLICT
            );
        }
        if (modelMapper.softDelete(modelId, principal.id(), LocalDateTime.now()) != 1) {
            throw new ServiceException("模型不存在或已被删除", HttpStatus.NOT_FOUND);
        }
    }

    /**
     * 处理{@code test}并返回对应结果。
     *
     * @param modelId 资源标识
     * @return 处理结果
     */
    public ModelConnectionView test(Long modelId) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(principal, context(modelId, null, "operate"));
        AgentModel model = requireModel(modelId);
        return executeTest(model);
    }

    /**
     * 处理{@code testConfig}并返回对应结果。
     *
     * @param request 请求参数
     * @return 处理结果
     */
    public ModelConnectionView testConfig(TestModelConfigRequest request) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(
            principal,
            context(request.existingModelId(), null, "operate")
        );
        AgentModel existing = request.existingModelId() == null
            ? null : requireModel(request.existingModelId());
        String credentialRef = selectApiKey(request.apiKey(), existing);
        AgentModel transientModel = new AgentModel();
        transientModel.setId(request.existingModelId());
        transientModel.setModelKey("unsaved-test");
        applyConfiguration(
            transientModel,
            "Unsaved model test",
            request.providerType(),
            request.modelName(),
            request.modelType(),
            request.endpointUrl(),
            credentialRef,
            request.contextSize(),
            request.maxOutputTokens(),
            request.reasoningConfig(),
            request.capabilities(),
            "testing"
        );
        return executeTest(transientModel);
    }

    /**
     * 处理{@code discover}并返回对应结果。
     *
     * @param request 请求参数
     * @return 符合条件的数据集合
     */
    public List<ModelOptionView> discover(DiscoverModelsRequest request) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        authorizationEnforcer.requireAllowed(
            principal,
            context(request.existingModelId(), null, "operate")
        );
        AgentModel existing = request.existingModelId() == null
            ? null : requireModel(request.existingModelId());
        String credentialRef = selectApiKey(request.apiKey(), existing);
        URI endpoint = endpointPolicy.normalize(request.providerType(), request.endpointUrl());
        try {
            String credential = credentialResolver.resolve(credentialRef);
            return providerClient.discover(endpoint, credential);
        } catch (ModelProviderException exception) {
            throw new ServiceException(exception.getMessage(), 502);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new ServiceException(exception.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 执行{@code Test}相关的处理流程。
     *
     * @param model 模型参数
     * @return 处理结果
     */
    private ModelConnectionView executeTest(AgentModel model) {
        long started = System.nanoTime();
        try {
            URI endpoint = endpointPolicy.normalize(model.getProviderType(), model.getEndpointUrl());
            String credential = credentialResolver.resolve(model.getCredentialRef());
            return providerClient.test(model, endpoint, credential);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            return ModelConnectionView.failure(exception.getMessage(), elapsed);
        } catch (RuntimeException exception) {
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            return ModelConnectionView.failure("模型连通性测试失败", elapsed);
        }
    }

    /**
     * 处理apply配置相关逻辑。
     *
     * @param model 模型参数
     * @param displayName 名称
     * @param providerType 业务类型
     * @param modelName 名称
     * @param modelType 业务类型
     * @param endpointUrl {@code endpointUrl}参数
     * @param apiKey 接口Key参数
     * @param contextSize 数量上限
     * @param maxOutputTokens {@code maxOutputTokens}参数
     * @param reasoningConfig {@code reasoningConfig}参数
     * @param capabilities {@code capabilities}参数
     * @param status 目标状态
     */
    private void applyConfiguration(
        AgentModel model,
        String displayName,
        String providerType,
        String modelName,
        String modelType,
        String endpointUrl,
        String apiKey,
        Integer contextSize,
        Integer maxOutputTokens,
        Map<String, Object> reasoningConfig,
        Map<String, Object> capabilities,
        String status
    ) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        requireText(displayName, 128, "模型显示名称");
        requireText(modelName, 255, "供应商模型名称");
        if (!MODEL_TYPES.contains(modelType)) {
            throw new ServiceException("不支持的模型类型", HttpStatus.BAD_REQUEST);
        }
        if (!STATUSES.contains(status)) {
            throw new ServiceException("模型状态无效", HttpStatus.BAD_REQUEST);
        }
        String normalizedApiKey = normalizeApiKey(apiKey);
        if (contextSize != null && (contextSize < 1 || contextSize > 10_000_000)) {
            throw new ServiceException("上下文长度超出允许范围", HttpStatus.BAD_REQUEST);
        }
        if (maxOutputTokens != null && (maxOutputTokens < 1 || maxOutputTokens > 1_000_000)) {
            throw new ServiceException("最大输出 Token 超出允许范围", HttpStatus.BAD_REQUEST);
        }
        URI endpoint = endpointPolicy.normalize(providerType, endpointUrl);
        Map<String, Object> validatedReasoning = configurationValidator.reasoning(reasoningConfig);
        Map<String, Object> validatedCapabilities = configurationValidator.capabilities(capabilities);
        model.setDisplayName(displayName.strip());
        model.setProviderType(providerType.strip());
        model.setModelName(modelName.strip());
        model.setModelType(modelType.strip());
        model.setEndpointUrl(endpoint.toString());
        model.setCredentialRef(normalizedApiKey);
        model.setContextSize(contextSize);
        model.setMaxOutputTokens(maxOutputTokens);
        model.setReasoningConfigJson(serialize(validatedReasoning, "推理配置"));
        model.setCapabilityJson(serialize(validatedCapabilities, "能力配置"));
        model.setStatus(status.strip());
    }

    /**
     * 处理{@code serialize}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String serialize(Map<String, Object> value, String label) {
        String json = jsonMapper.writeValueAsString(value);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw new ServiceException(label + "超过 32KB 限制", HttpStatus.BAD_REQUEST);
        }
        return json;
    }

    /**
     * 获取接口Key。
     *
     * @param requestedApiKey requested接口Key参数
     * @param existing {@code existing}参数
     * @return 处理结果
     */
    private String selectApiKey(String requestedApiKey, AgentModel existing) {
        return apiKeyValue(requestedApiKey, existing == null ? null : existing.getCredentialRef());
    }

    /**
     * 处理接口KeyValue并返回对应结果。
     *
     * @param apiKey 接口Key参数
     * @param existingValue {@code existingValue}参数
     * @return 处理结果
     */
    private String apiKeyValue(String apiKey, String existingValue) {
        if (apiKey != null && !apiKey.isBlank()) {
            return normalizeApiKey(apiKey);
        }
        if (isStoredApiKey(existingValue)) {
            return existingValue.strip();
        }
        throw new ServiceException("必须提供模型 API Key", HttpStatus.BAD_REQUEST);
    }

    /**
     * 处理normalize接口Key并返回对应结果。
     *
     * @param apiKey 接口Key参数
     * @return 处理结果
     */
    private String normalizeApiKey(String apiKey) {
        if (!isStoredApiKey(apiKey)) {
            throw new ServiceException("模型 API Key 无效，请重新填写", HttpStatus.BAD_REQUEST);
        }
        return apiKey.strip();
    }

    /**
     * 判断Stored接口Key是否满足要求。
     *
     * @param value {@code value}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isStoredApiKey(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.strip();
        return normalized.length() <= MAX_API_KEY_LENGTH
            && !normalized.startsWith("v1s.")
            && !normalized.startsWith("env:");
    }

    /**
     * 校验模型，并在条件不满足时终止处理。
     *
     * @param modelId 资源标识
     * @return 处理结果
     */
    private AgentModel requireModel(Long modelId) {
        AgentModel model = modelMapper.selectModelById(modelId);
        if (model == null) {
            throw new ServiceException("模型不存在", HttpStatus.NOT_FOUND);
        }
        return model;
    }

    /**
     * 处理上下文并返回对应结果。
     *
     * @param resourceId 资源标识
     * @param resourceKey 资源Key参数
     * @param action {@code action}参数
     * @return 处理结果
     */
    private PermissionContext context(Long resourceId, String resourceKey, String action) {
        return new PermissionContext(
            "model", resourceId, resourceKey, action, ResourceState.ACTIVE, true, Set.of(), null
        );
    }

    /**
     * 处理{@code normalizeFilter}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalizeFilter(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * 处理{@code normalizeSearch}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalizeSearch(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > 128) {
            throw new ServiceException("搜索条件超过 128 字符限制", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    /**
     * 校验{@code Text}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param maximumLength {@code maximumLength}参数
     * @param label {@code label}参数
     */
    private void requireText(String value, int maximumLength, String label) {
        if (value == null || value.isBlank() || value.strip().length() > maximumLength) {
            throw new ServiceException(label + "无效", HttpStatus.BAD_REQUEST);
        }
    }
}
