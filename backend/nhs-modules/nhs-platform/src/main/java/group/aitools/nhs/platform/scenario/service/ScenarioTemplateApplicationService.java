package group.aitools.nhs.platform.scenario.service;

import group.aitools.nhs.platform.agent.service.AgentApplicationService;
import group.aitools.nhs.platform.agent.web.AgentResourceBindingRequest;
import group.aitools.nhs.platform.agent.web.AgentView;
import group.aitools.nhs.platform.agent.web.CreateAgentRequest;
import group.aitools.nhs.platform.agent.web.SaveAgentVersionRequest;
import group.aitools.nhs.platform.agent.web.AgentVersionPublishResult;
import group.aitools.nhs.platform.agent.web.AgentVersionView;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.connector.service.ToolCatalogService;
import group.aitools.nhs.platform.connector.web.ToolView;
import group.aitools.nhs.platform.data.service.DataSourceCatalogService;
import group.aitools.nhs.platform.data.web.DatasetView;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.knowledge.service.KnowledgeApplicationService;
import group.aitools.nhs.platform.knowledge.web.KnowledgeBaseView;
import group.aitools.nhs.platform.model.service.ModelApplicationService;
import group.aitools.nhs.platform.model.web.ModelView;
import group.aitools.nhs.platform.scenario.domain.AgentScenarioInstallRun;
import group.aitools.nhs.platform.scenario.domain.AgentScenarioInstance;
import group.aitools.nhs.platform.scenario.domain.AgentScenarioUninstallRun;
import group.aitools.nhs.platform.scenario.mapper.ScenarioTemplateMapper;
import group.aitools.nhs.platform.scenario.web.ScenarioTemplateRequest;
import group.aitools.nhs.platform.scenario.web.ScenarioTemplateUninstallRequest;
import group.aitools.nhs.platform.scenario.web.ScenarioTemplateViews;
import group.aitools.nhs.platform.scenario.web.ScenarioTemplateViews.Check;
import group.aitools.nhs.platform.scenario.web.ScenarioTemplateViews.Detail;
import group.aitools.nhs.platform.scenario.web.ScenarioTemplateViews.Instance;
import group.aitools.nhs.platform.scenario.web.ScenarioTemplateViews.Install;
import group.aitools.nhs.platform.scenario.web.ScenarioTemplateViews.Precheck;
import group.aitools.nhs.platform.scenario.web.ScenarioTemplateViews.ResourceOption;
import group.aitools.nhs.platform.scenario.web.ScenarioTemplateViews.ResourceOptions;
import group.aitools.nhs.platform.scenario.web.ScenarioTemplateViews.ResourceRequirement;
import group.aitools.nhs.platform.scenario.web.ScenarioTemplateViews.Summary;
import group.aitools.nhs.platform.scenario.web.ScenarioTemplateViews.Uninstall;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 负责Scenario模板相关的业务编排与领域规则处理。
 * Scenario-template catalog plus an idempotent Agent delivery workflow. */
@Service
public class ScenarioTemplateApplicationService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {
    };
    private static final Pattern INSTANCE_KEY = Pattern.compile("[a-z][a-z0-9._-]{0,127}");

    private final CurrentPrincipalProvider principalProvider;
    private final PlatformIdGenerator idGenerator;
    private final ScenarioTemplateMapper mapper;
    private final AgentApplicationService agentService;
    private final ModelApplicationService modelService;
    private final DataSourceCatalogService dataSourceService;
    private final KnowledgeApplicationService knowledgeService;
    private final ToolCatalogService toolService;
    private final JsonMapper jsonMapper;
    private final ScenarioTemplateAuditService auditService;

    /**
     * 创建 {@code ScenarioTemplateApplicationService} 实例并初始化所需依赖。
     *
     * @param principalProvider 操作主体提供方参数
     * @param idGenerator {@code idGenerator}参数
     * @param mapper {@code mapper}参数
     * @param agentService 智能体Service参数
     * @param modelService 模型Service参数
     * @param dataSourceService 数据数据源Service参数
     * @param knowledgeService 知识库Service参数
     * @param toolService 工具Service参数
     * @param jsonMapper {@code jsonMapper}参数
     * @param auditService 审计Service参数
     */
    public ScenarioTemplateApplicationService(
        CurrentPrincipalProvider principalProvider,
        PlatformIdGenerator idGenerator,
        ScenarioTemplateMapper mapper,
        AgentApplicationService agentService,
        ModelApplicationService modelService,
        DataSourceCatalogService dataSourceService,
        KnowledgeApplicationService knowledgeService,
        ToolCatalogService toolService,
        JsonMapper jsonMapper,
        ScenarioTemplateAuditService auditService
    ) {
        this.principalProvider = principalProvider;
        this.idGenerator = idGenerator;
        this.mapper = mapper;
        this.agentService = agentService;
        this.modelService = modelService;
        this.dataSourceService = dataSourceService;
        this.knowledgeService = knowledgeService;
        this.toolService = toolService;
        this.jsonMapper = jsonMapper;
        this.auditService = auditService;
    }

    /**
     * 查询{@code Templates}列表。
     *
     * @return 符合条件的数据集合
     */
    public List<Summary> listTemplates() {
        return ScenarioTemplateCatalog.all().stream().map(ScenarioTemplateCatalog.Definition::summary).toList();
    }

    /**
     * 获取模板。
     *
     * @param templateKey 模板Key参数
     * @return 处理结果
     */
    public Detail getTemplate(String templateKey) {
        ScenarioTemplateCatalog.Definition definition = definition(templateKey);
        return new Detail(definition.summary(), definition.manifest());
    }

    /**
     * 处理资源Options并返回对应结果。
     *
     * @param templateKey 模板Key参数
     * @return 处理结果
     */
    public ResourceOptions resourceOptions(String templateKey) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        ScenarioTemplateCatalog.Definition definition = definition(templateKey);
        Set<String> types = definition.requiredResources().stream()
            .map(ResourceRequirement::type).collect(java.util.stream.Collectors.toSet());
        Map<String, List<ResourceOption>> options = new LinkedHashMap<>();
        if (types.contains("metadata_dataset")) {
            options.put("metadata_dataset", dataSourceService.listDatasets(200).stream()
                .map(dataset -> new ResourceOption(
                    String.valueOf(dataset.id()), dataset.datasetKey(), dataset.name(), dataset.description(),
                    dataset.status(), Map.of("dataSourceId", dataset.dataSourceId())))
                .toList());
        }
        if (types.contains("knowledge_base")) {
            options.put("knowledge_base", knowledgeService.list(null, false, 200).stream()
                .map(base -> new ResourceOption(
                    String.valueOf(base.id()), base.knowledgeKey(), base.name(), base.description(),
                    base.status(), Map.of("visibility", base.visibility())))
                .toList());
        }
        if (types.contains("api_tool") || types.contains("mcp_tool")) {
            List<ResourceOption> tools = toolService.available(null, null, null, 200).stream()
                .filter(tool -> Set.of("api", "mcp").contains(tool.toolType()))
                .map(tool -> new ResourceOption(
                    String.valueOf(tool.id()), tool.toolKey(), tool.name(), tool.description(),
                    tool.status(), Map.of("riskLevel", tool.riskLevel(), "toolType", tool.toolType())))
                .toList();
            if (types.contains("api_tool")) {
                options.put("api_tool", tools.stream().filter(item -> "api".equals(item.meta().get("toolType"))).toList());
            }
            if (types.contains("mcp_tool")) {
                options.put("mcp_tool", tools.stream().filter(item -> "mcp".equals(item.meta().get("toolType"))).toList());
            }
        }
        if (types.contains("skill")) {
            options.put("skill", List.of());
        }
        if (types.contains("notification")) {
            options.put("notification", List.of(
                new ResourceOption("dingtalk", "dingtalk", "钉钉通知", "用于巡检摘要和风险提醒。", "available", Map.of()),
                new ResourceOption("wechat_work", "wechat_work", "企业微信通知", "用于团队群消息和个人提醒。", "available", Map.of())
            ));
        }
        if (types.contains("feedback")) {
            options.put("feedback", List.of(
                new ResourceOption("chat_feedback", "chat_feedback", "对话反馈", "使用平台内置点赞点踩和问题反馈。", "available", Map.of())
            ));
        }
        return new ResourceOptions(definition.key(), options);
    }

    /**
     * 处理{@code precheck}并返回对应结果。
     *
     * @param templateKey 模板Key参数
     * @param request 请求参数
     * @return 处理结果
     */
    public Precheck precheck(String templateKey, ScenarioTemplateRequest request) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        ScenarioTemplateCatalog.Definition definition = definition(templateKey);
        String instanceKey = instanceKey(definition, request);
        List<Check> checks = new ArrayList<>();
        boolean canInstall = true;
        AgentView existing = findAgent(instanceKey);
        if (existing == null) {
            checks.add(new Check("agent", "智能体实例", "success", "将创建智能体 " + instanceKey + "。"));
        } else if (!definition.key().equals(String.valueOf(existing.engineConfig().get("scenarioTemplateId")))) {
            canInstall = false;
            checks.add(new Check("agent", "智能体实例", "error", "实例标识已被其他智能体占用，请更换标识。"));
        } else {
            checks.add(new Check("agent", "智能体实例", "warning", "将复用当前模板已安装的智能体。"));
        }

        List<ModelView> models = modelService.list("chat", null, null, false, 100);
        if (models.isEmpty()) {
            canInstall = false;
            checks.add(new Check("model", "对话模型", "error", "没有可用的对话模型，请先配置模型。"));
        } else {
            checks.add(new Check("model", "对话模型", "success", "将使用已授权的 " + models.getFirst().displayName() + "。"));
        }

        List<ResourceRequirement> missing = missingResources(definition, request);
        if (!missing.isEmpty()) {
            canInstall = false;
            checks.add(new Check("resources", "资源绑定", "error", "缺少必选资源：" + missing.stream().map(ResourceRequirement::name).collect(java.util.stream.Collectors.joining("、"))));
        } else {
            checks.add(new Check("resources", "资源绑定", "success", "必选资源已完成绑定。"));
        }
        checks.add(new Check("runtime", "运行时", "success", "安装将复用现有 AgentScope Java 运行时和权限快照。"));
        return new Precheck(definition.key(), instanceKey, canInstall, List.copyOf(checks));
    }

    /**
     * 查询{@code Instances}列表。
     *
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<Instance> listInstances(int limit) {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        return mapper.selectInstances(principal.id(), isAdmin(principal), Math.min(limit, 200)).stream()
            .map(this::instanceView).toList();
    }

    /**
     * 获取{@code Instance}。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    public Instance getInstance(Long id) {
        AgentScenarioInstance instance = requireInstance(id);
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        if (!isAdmin(principal) && !Objects.equals(principal.id(), instance.getOwnerId())) {
            throw new ServiceException("无权查看该场景交付实例", HttpStatus.FORBIDDEN);
        }
        return instanceView(instance);
    }

    /**
     * 处理{@code install}并返回对应结果。
     *
     * @param templateKey 模板Key参数
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public Install install(String templateKey, ScenarioTemplateRequest request) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        ScenarioTemplateCatalog.Definition definition = definition(templateKey);
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        Precheck precheck = precheck(templateKey, request);
        String key = instanceKey(definition, request);
        String idem = request.idempotencyKey() == null || request.idempotencyKey().isBlank()
            ? digest(templateKey + ":" + key + ":" + request.resourceBindings()) : request.idempotencyKey().strip();
        AgentScenarioInstallRun prior = mapper.selectRunByIdempotency(definition.key(), idem);
        if (prior != null && "succeeded".equals(prior.getStatus())) {
            AgentScenarioInstance existing = requireInstance(prior.getInstanceId());
            return installView(definition, existing, prior, true);
        }
        if (!precheck.canInstall()) {
            throw new ServiceException("场景模板预检未通过", HttpStatus.CONFLICT);
        }
        AgentScenarioInstance instance = mapper.lockInstance(definition.key(), key);
        boolean created = instance == null;
        LocalDateTime now = LocalDateTime.now();
        if (instance == null) {
            instance = new AgentScenarioInstance();
            instance.setId(idGenerator.nextId());
            instance.setTemplateKey(definition.key());
            instance.setInstanceKey(key);
            instance.setDisplayName(textOr(request.displayName(), definition.name()));
            instance.setDescription(textOr(request.description(), definition.description()));
            instance.setOwnerId(principal.id());
            instance.setCreatedAt(now);
            instance.setDelFlag("0");
        } else if (!isAdmin(principal) && !Objects.equals(principal.id(), instance.getOwnerId())) {
            throw new ServiceException("该场景实例由其他用户管理", HttpStatus.FORBIDDEN);
        }

        AgentView agent = findAgent(key);
        if (agent == null) {
            agent = agentService.create(new CreateAgentRequest(
                key, instance.getDisplayName(), instance.getDescription(), definition.agentType(),
                "agentscope_java", null, false, 0,
                Map.of("scenarioTemplateId", definition.key(), "scenarioCategory", definition.category())
            ));
        }
        ModelView model = modelService.list("chat", null, null, false, 100).getFirst();
        List<AgentResourceBindingRequest> toolBindings = bindingsForTools(definition, request);
        List<AgentResourceBindingRequest> knowledgeBindings = numericBindings(request.resourceBindings(), "knowledge_base", "read");
        List<AgentResourceBindingRequest> skillBindings = numericBindings(request.resourceBindings(), "skill", "use");
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("scenarioTemplateId", definition.key());
        runtime.put("scenarioCategory", definition.category());
        runtime.put("resourceBindings", request.resourceBindings());
        runtime.put("temperature", 0.0);
        AgentVersionView version = agentService.createVersion(agent.id(), new SaveAgentVersionRequest(
            definition.systemPrompt(), model.id(), null, runtime,
            Map.of("message", definition.name(), "suggestions", definition.sampleQuestions(), "showSuggestions", true),
            List.of(definition.category().toLowerCase(Locale.ROOT)), toolBindings, skillBindings, knowledgeBindings
        ));
        if (request.publish()) {
            AgentVersionPublishResult published = agentService.publish(agent.id(), version.id());
            version = published.version();
        }
        instance.setAgentId(agent.id());
        instance.setAgentVersionId(version.id());
        instance.setStatus("installed");
        instance.setResourceBindingsJson(writeJson(request.resourceBindings()));
        instance.setAcceptanceCriteriaJson(writeJson(definition.summary().acceptanceCriteria()));
        instance.setSampleQuestionsJson(writeJson(definition.sampleQuestions()));
        instance.setNextStepsJson(writeJson(List.of("进入智能体中心检查配置", "使用样例问题完成验收")));
        instance.setUpdatedAt(now);
        if (created) {
            try {
                mapper.insertInstance(instance);
            } catch (DuplicateKeyException exception) {
                throw new ServiceException("场景实例已被其他用户创建，请刷新后重试", HttpStatus.CONFLICT);
            }
        } else if (mapper.updateInstance(instance) != 1) {
            throw new ServiceException("场景实例已被其他请求修改", HttpStatus.CONFLICT);
        }
        AgentScenarioInstallRun run = new AgentScenarioInstallRun();
        run.setId(idGenerator.nextId());
        run.setInstanceId(instance.getId());
        run.setTemplateKey(definition.key());
        run.setIdempotencyKey(idem);
        run.setStatus("succeeded");
        run.setPrecheckJson(writeJson(precheck));
        run.setResourceBindingsJson(writeJson(request.resourceBindings()));
        run.setCreatedBy(principal.id());
        run.setCreatedAt(now);
        run.setCompletedAt(LocalDateTime.now());
        mapper.insertRun(run);
        auditService.record(
            principal,
            "scenario.install",
            instance.getId(),
            "success",
            "场景模板交付完成",
            "template=" + definition.key() + ", instance=" + instance.getInstanceKey()
        );
        return installView(definition, instance, run, created);
    }

    /**
 * 处理{@code uninstall}并返回对应结果。
 *
     * Disable a delivered scenario instance and, when it is still owned by the
     * scenario workflow, disable the generated Agent as well. The instance is
     * retained for audit and can never be silently reactivated by a stale page.
     */
    @Transactional(rollbackFor = Exception.class)
    public Uninstall uninstall(Long instanceId, ScenarioTemplateUninstallRequest request) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        if (request == null || !request.confirm()) {
            throw new ServiceException("必须确认卸载场景实例", HttpStatus.BAD_REQUEST);
        }
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        AgentScenarioInstance instance = mapper.lockInstanceById(instanceId);
        if (instance == null) {
            throw new ServiceException("场景交付实例不存在", HttpStatus.NOT_FOUND);
        }
        if (!isAdmin(principal) && !Objects.equals(principal.id(), instance.getOwnerId())) {
            auditService.record(principal, "scenario.uninstall", instanceId, "deny", "无权卸载该场景实例", "owner=" + instance.getOwnerId());
            throw new ServiceException("无权卸载该场景交付实例", HttpStatus.FORBIDDEN);
        }
        String reason = textOr(request.reason(), "用户请求卸载场景实例");
        String idempotencyKey = request.idempotencyKey() == null || request.idempotencyKey().isBlank()
            ? digest("uninstall:" + instanceId + ":" + reason)
            : request.idempotencyKey().strip();
        AgentScenarioUninstallRun prior = mapper.selectUninstallRunByIdempotency(instanceId, idempotencyKey);
        if (prior != null && "succeeded".equals(prior.getStatus())) {
            return uninstallView(prior, true);
        }
        if (prior != null) {
            throw new ServiceException("该幂等键对应的卸载运行未成功，请使用新的幂等键重试", HttpStatus.CONFLICT);
        }

        String previousStatus = instance.getStatus();
        String agentStatus = "not_found";
        String warning = null;
        if (instance.getAgentId() != null) {
            AgentView agent = findScenarioAgent(instance.getAgentId());
            if (agent != null) {
                Object templateOwner = agent.engineConfig().get("scenarioTemplateId");
                if (templateOwner != null && !instance.getTemplateKey().equals(String.valueOf(templateOwner))) {
                    auditService.record(principal, "scenario.uninstall", instanceId, "deny", "Agent 归属与场景模板不一致", "agent=" + instance.getAgentId());
                    throw new ServiceException("生成的 Agent 已被其他场景或配置接管，无法自动卸载", HttpStatus.CONFLICT);
                }
                if (!"archived".equals(agent.status()) && !"disabled".equals(agent.status())) {
                    agentService.updateStatus(agent.id(), "disabled");
                }
                agentStatus = "archived".equals(agent.status()) ? "archived" : "disabled";
            } else {
                warning = "关联 Agent 已不存在，仅停用场景实例记录。";
            }
        } else {
            warning = "场景实例没有关联 Agent，仅停用场景实例记录。";
        }
        if (!"disabled".equals(previousStatus) && mapper.updateInstanceStatus(instanceId, "disabled", LocalDateTime.now()) != 1) {
            throw new ServiceException("场景实例已被并发修改，请刷新后重试", HttpStatus.CONFLICT);
        }
        AgentScenarioUninstallRun run = new AgentScenarioUninstallRun();
        run.setId(idGenerator.nextId());
        run.setInstanceId(instanceId);
        run.setTemplateKey(instance.getTemplateKey());
        run.setIdempotencyKey(idempotencyKey);
        run.setStatus("succeeded");
        run.setReason(reason);
        run.setPreviousStatus(previousStatus);
        run.setAgentStatus(agentStatus);
        run.setWarning(warning);
        run.setCreatedBy(principal.id());
        run.setCreatedAt(LocalDateTime.now());
        run.setCompletedAt(LocalDateTime.now());
        mapper.insertUninstallRun(run);
        auditService.record(
            principal,
            "scenario.uninstall",
            instanceId,
            "success",
            reason,
            "template=" + instance.getTemplateKey() + ", agent_status=" + agentStatus
        );
        return uninstallView(run, false);
    }

    /**
     * 获取Scenario智能体。
     *
     * @param agentId 资源标识
     * @return 处理结果
     */
    private AgentView findScenarioAgent(Long agentId) {
        try {
            return agentService.get(agentId);
        } catch (ServiceException exception) {
            if (Objects.equals(exception.getCode(), HttpStatus.NOT_FOUND)) {
                return null;
            }
            throw exception;
        }
    }

    /**
     * 处理{@code uninstallView}并返回对应结果。
     *
     * @param run {@code run}参数
     * @param idempotent {@code idempotent}参数
     * @return 处理结果
     */
    private Uninstall uninstallView(AgentScenarioUninstallRun run, boolean idempotent) {
        return new Uninstall(
            String.valueOf(run.getInstanceId()),
            run.getTemplateKey(),
            run.getStatus(),
            run.getAgentStatus(),
            run.getPreviousStatus(),
            idempotent,
            run.getWarning(),
            String.valueOf(run.getId()),
            run.getReason()
        );
    }

    /**
     * 处理{@code installView}并返回对应结果。
     *
     * @param definition 定义参数
     * @param instance {@code instance}参数
     * @param run {@code run}参数
     * @param created {@code created}参数
     * @return 处理结果
     */
    private Install installView(ScenarioTemplateCatalog.Definition definition, AgentScenarioInstance instance, AgentScenarioInstallRun run, boolean created) {
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("id", instance.getAgentId());
        agent.put("name", instance.getDisplayName());
        agent.put("description", instance.getDescription());
        agent.put("version_id", instance.getAgentVersionId());
        Map<String, Object> instanceMap = new LinkedHashMap<>();
        instanceMap.put("id", instance.getId());
        instanceMap.put("template_id", instance.getTemplateKey());
        instanceMap.put("instance_key", instance.getInstanceKey());
        instanceMap.put("display_name", instance.getDisplayName());
        instanceMap.put("status", instance.getStatus());
        Map<String, Object> runMap = new LinkedHashMap<>();
        runMap.put("id", run.getId());
        runMap.put("status", run.getStatus());
        runMap.put("created_at", run.getCreatedAt());
        Map<String, Object> version = Map.of("id", instance.getAgentVersionId(), "status", "published");
        Map<String, Object> bindings = readMap(instance.getResourceBindingsJson());
        return new Install(definition.key(), created, instanceMap, runMap, agent, version, bindings,
            List.of(), List.of("进入智能体中心检查配置", "使用样例问题完成验收"), definition.tools(), definition.sampleQuestions(), resourceSummary(bindings));
    }

    /**
     * 处理{@code instanceView}并返回对应结果。
     *
     * @param instance {@code instance}参数
     * @return 处理结果
     */
    private Instance instanceView(AgentScenarioInstance instance) {
        ScenarioTemplateCatalog.Definition definition = definition(instance.getTemplateKey());
        AgentScenarioInstallRun run = mapper.selectLatestRun(instance.getId());
        Map<String, Object> agent = Map.of("id", instance.getAgentId(), "name", instance.getDisplayName(), "version_id", instance.getAgentVersionId());
        Map<String, Object> latestRun = run == null ? Map.of() : Map.of("id", run.getId(), "status", run.getStatus(), "created_at", run.getCreatedAt());
        return new Instance(String.valueOf(instance.getId()), instance.getTemplateKey(), definition.name(), instance.getStatus(), String.valueOf(instance.getOwnerId()), agent, latestRun,
            resourceSummary(readMap(instance.getResourceBindingsJson())), readList(instance.getAcceptanceCriteriaJson()), readList(instance.getSampleQuestionsJson()), readList(instance.getNextStepsJson()));
    }

    /**
     * 处理{@code missingResources}并返回对应结果。
     *
     * @param definition 定义参数
     * @param request 请求参数
     * @return 符合条件的数据集合
     */
    private List<ResourceRequirement> missingResources(ScenarioTemplateCatalog.Definition definition, ScenarioTemplateRequest request) {
        List<ResourceRequirement> result = new ArrayList<>();
        for (ResourceRequirement requirement : definition.requiredResources()) {
            if (requirement.required() && values(request.resourceBindings(), requirement.type()).isEmpty()) {
                result.add(requirement);
            }
        }
        return result;
    }

    /**
     * 处理{@code bindingsForTools}并返回对应结果。
     *
     * @param definition 定义参数
     * @param request 请求参数
     * @return 符合条件的数据集合
     */
    private List<AgentResourceBindingRequest> bindingsForTools(ScenarioTemplateCatalog.Definition definition, ScenarioTemplateRequest request) {
        LinkedHashMap<Long, AgentResourceBindingRequest> result = new LinkedHashMap<>();
        numericBindings(request.resourceBindings(), "api_tool", "invoke").forEach(item -> result.put(item.resourceId(), item));
        numericBindings(request.resourceBindings(), "mcp_tool", "invoke").forEach(item -> result.put(item.resourceId(), item));
        List<ToolView> available = toolService.available(null, null, null, 200);
        for (String key : definition.tools()) {
            available.stream().filter(tool -> key.equals(tool.toolKey())).findFirst().ifPresent(tool ->
                result.putIfAbsent(tool.id(), new AgentResourceBindingRequest(tool.id(), "invoke", Map.of("enabled", true))));
        }
        return List.copyOf(result.values());
    }

    /**
     * 处理{@code numericBindings}并返回对应结果。
     *
     * @param bindings {@code bindings}参数
     * @param name 名称
     * @param permission 权限参数
     * @return 符合条件的数据集合
     */
    private List<AgentResourceBindingRequest> numericBindings(Map<String, Object> bindings, String name, String permission) {
        return values(bindings, name).stream().map(value -> {
            try {
                return new AgentResourceBindingRequest(Long.valueOf(value), permission, Map.of("enabled", true));
            } catch (NumberFormatException exception) {
                throw new ServiceException(name + " 资源标识必须是数字", HttpStatus.BAD_REQUEST);
            }
        }).toList();
    }

    /**
     * 处理{@code values}并返回对应结果。
     *
     * @param bindings {@code bindings}参数
     * @param key {@code key}参数
     * @return 符合条件的数据集合
     */
    private List<String> values(Map<String, Object> bindings, String key) {
        Object raw = bindings.get(key);
        if (raw == null) return List.of();
        if (raw instanceof Collection<?> collection) return collection.stream().map(String::valueOf).filter(value -> !value.isBlank()).toList();
        String value = String.valueOf(raw).strip();
        return value.isBlank() ? List.of() : List.of(value);
    }

    /**
     * 获取智能体。
     *
     * @param key {@code key}参数
     * @return 处理结果
     */
    private AgentView findAgent(String key) {
        return agentService.list(key, true, 200).stream()
            .filter(agent -> key.equalsIgnoreCase(agent.agentKey()) || key.equalsIgnoreCase(agent.name()))
            .findFirst().orElse(null);
    }

    /**
     * 校验{@code Instance}，并在条件不满足时终止处理。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    private AgentScenarioInstance requireInstance(Long id) {
        AgentScenarioInstance instance = mapper.selectInstance(id);
        if (instance == null) throw new ServiceException("场景交付实例不存在", HttpStatus.NOT_FOUND);
        return instance;
    }

    /**
     * 处理定义并返回对应结果。
     *
     * @param key {@code key}参数
     * @return 处理结果
     */
    private ScenarioTemplateCatalog.Definition definition(String key) {
        return ScenarioTemplateCatalog.all().stream().filter(item -> item.key().equals(key)).findFirst()
            .orElseThrow(() -> new ServiceException("场景模板不存在", HttpStatus.NOT_FOUND));
    }

    /**
     * 处理{@code instanceKey}并返回对应结果。
     *
     * @param definition 定义参数
     * @param request 请求参数
     * @return 处理结果
     */
    private String instanceKey(ScenarioTemplateCatalog.Definition definition, ScenarioTemplateRequest request) {
        String value = request.instanceKey() == null || request.instanceKey().isBlank() ? definition.key() : request.instanceKey().strip().toLowerCase(Locale.ROOT);
        if (!INSTANCE_KEY.matcher(value).matches()) throw new ServiceException("实例标识仅支持小写字母、数字、点、短横线和下划线", HttpStatus.BAD_REQUEST);
        return value;
    }

    /**
     * 判断{@code Admin}是否满足要求。
     *
     * @param principal 当前操作主体
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isAdmin(CurrentPrincipal principal) {
        return principal.hasRole(PlatformRole.PLATFORM_ADMIN);
    }

    /**
     * 处理{@code textOr}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param fallback {@code fallback}参数
     * @return 处理结果
     */
    private String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    /**
     * 处理{@code writeJson}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String writeJson(Object value) {
        return jsonMapper.writeValueAsString(value);
    }

    /**
     * 处理{@code readMap}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private Map<String, Object> readMap(String value) {
        return value == null || value.isBlank() ? Map.of() : jsonMapper.readValue(value, MAP_TYPE);
    }

    /**
     * 处理{@code readList}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 符合条件的数据集合
     */
    private List<String> readList(String value) {
        return value == null || value.isBlank() ? List.of() : jsonMapper.readValue(value, LIST_TYPE);
    }

    /**
     * 处理{@code digest}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : hash) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成场景交付幂等键", exception);
        }
    }

    /**
     * 处理资源Summary并返回对应结果。
     *
     * @param bindings {@code bindings}参数
     * @return 符合条件的数据集合
     */
    private List<Map<String, Object>> resourceSummary(Map<String, Object> bindings) {
        return bindings.entrySet().stream().map(entry -> Map.of("type", entry.getKey(), "value", entry.getValue())).toList();
    }
}
