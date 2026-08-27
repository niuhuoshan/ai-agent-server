package group.aitools.nhs.platform.runtime.question.service;

import group.aitools.nhs.platform.common.ContentHashing;
import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.conversation.service.ConversationTurnApplicationService;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.platform.runtime.question.domain.AgentRuntimeUserQuestion;
import group.aitools.nhs.platform.runtime.question.domain.UserQuestionCreateCommand;
import group.aitools.nhs.platform.runtime.question.mapper.AgentRuntimeUserQuestionMapper;
import group.aitools.nhs.platform.runtime.question.web.UserQuestionAnswerRequest;
import group.aitools.nhs.platform.runtime.question.web.UserQuestionCancelRequest;
import group.aitools.nhs.platform.runtime.question.web.UserQuestionDecisionResult;
import group.aitools.nhs.platform.runtime.question.web.UserQuestionView;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 负责运行时用户追问相关的业务编排与领域规则处理。
 * Owner-bound, idempotent state machine for the ask_user_question interaction. */
@Service
public class RuntimeUserQuestionApplicationService {

    private static final TypeReference<List<Map<String, Object>>> OPTIONS = new TypeReference<>() { };
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(15);
    private static final Duration MIN_TTL = Duration.ofMinutes(1);
    private static final Duration MAX_TTL = Duration.ofHours(1);

    private final CurrentPrincipalProvider principalProvider;
    private final PlatformIdGenerator idGenerator;
    private final AgentRuntimeUserQuestionMapper mapper;
    private final JsonMapper jsonMapper;
    private final ConversationTurnApplicationService conversationTurnService;

    @Autowired
    public RuntimeUserQuestionApplicationService(
        CurrentPrincipalProvider principalProvider,
        PlatformIdGenerator idGenerator,
        AgentRuntimeUserQuestionMapper mapper,
        JsonMapper jsonMapper,
        ConversationTurnApplicationService conversationTurnService
    ) {
        this.principalProvider = principalProvider;
        this.idGenerator = idGenerator;
        this.mapper = mapper;
        this.jsonMapper = jsonMapper;
        this.conversationTurnService = conversationTurnService;
    }

    /**
 * 创建 {@code RuntimeUserQuestionApplicationService} 实例并初始化所需依赖。
 * Compatibility constructor for focused state-machine tests. */
    public RuntimeUserQuestionApplicationService(
        CurrentPrincipalProvider principalProvider,
        PlatformIdGenerator idGenerator,
        AgentRuntimeUserQuestionMapper mapper,
        JsonMapper jsonMapper
    ) {
        this(principalProvider, idGenerator, mapper, jsonMapper, null);
    }

    /**
 * 创建并保存{@code create}。
 *
     * Creates a durable pending question for the runtime. The runtime supplies the
     * already-authorized owner id; HTTP answer/cancel operations are always checked
     * against the current human principal.
     */
    @Transactional(rollbackFor = Exception.class)
    public UserQuestionView create(UserQuestionCreateCommand command) {
        // 以下流程按业务条件逐级校验并选择处理分支，条件不满足时会提前终止。
        validateCreate(command);
        LocalDateTime now = LocalDateTime.now();
        AgentRuntimeUserQuestion existing = mapper.selectByCreateIdempotency(
            command.ownerId(), command.conversationId(), command.idempotencyKey()
        );
        if (existing != null) {
            return UserQuestionView.from(existing, jsonMapper);
        }
        LocalDateTime expiresAt = command.expiresAt() == null
            ? now.plus(DEFAULT_TTL) : command.expiresAt();
        if (!expiresAt.isAfter(now.plus(MIN_TTL)) || expiresAt.isAfter(now.plus(MAX_TTL))) {
            throw badRequest("问题有效期必须在1分钟到1小时之间");
        }
        String questionId = normalize(command.questionId());
        if (questionId == null) {
            questionId = "uq_" + idGenerator.nextUuid().replace("-", "");
        }
        String optionsJson = jsonMapper.writeValueAsString(command.options());
        mapper.expirePending(command.ownerId(), command.conversationId(), now);
        mapper.supersedePending(command.ownerId(), command.conversationId(), now);
        AgentRuntimeUserQuestion value = new AgentRuntimeUserQuestion();
        value.setId(idGenerator.nextId());
        value.setQuestionId(questionId);
        value.setOwnerId(command.ownerId());
        value.setConversationId(command.conversationId());
        value.setExecutionId(normalize(command.executionId()));
        value.setConversationTurnId(command.conversationTurnId());
        value.setToolCallId(normalize(command.toolCallId()));
        value.setIdempotencyKey(command.idempotencyKey().strip());
        value.setQuestion(command.question().strip());
        value.setOptionsJson(optionsJson);
        value.setMultiSelect(command.multiSelect());
        value.setAllowCustomInput(command.allowCustomInput());
        value.setContext(normalize(command.context()));
        value.setPurpose(normalize(command.purpose()));
        value.setExpiresAt(expiresAt);
        value.setCreatedAt(now);
        value.setUpdatedAt(now);
        if (mapper.insertQuestion(
            value.getId(), value.getQuestionId(), value.getOwnerId(), value.getConversationId(),
            value.getExecutionId(), value.getConversationTurnId(), value.getToolCallId(),
            value.getIdempotencyKey(), value.getQuestion(), value.getOptionsJson(),
            value.isMultiSelect(), value.isAllowCustomInput(), value.getContext(), value.getPurpose(),
            value.getExpiresAt(), now
        ) != 1) {
            AgentRuntimeUserQuestion raced = mapper.selectByCreateIdempotency(
                value.getOwnerId(), value.getConversationId(), value.getIdempotencyKey()
            );
            if (raced == null) {
                raced = mapper.selectByQuestionId(questionId);
            }
            if (raced != null && sameCreate(value, raced)) {
                return UserQuestionView.from(raced, jsonMapper);
            }
            throw conflict("问题标识或幂等键已被使用");
        }
        return UserQuestionView.from(value, jsonMapper);
    }

    /**
     * 获取{@code get}。
     *
     * @param questionId 资源标识
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public UserQuestionView get(String questionId) {
        CurrentPrincipal principal = requireHuman();
        AgentRuntimeUserQuestion question = owned(questionId, principal.id());
        question = expireIfNeeded(question, principal.id());
        return UserQuestionView.from(question, jsonMapper);
    }

    /**
     * 处理{@code pending}并返回对应结果。
     *
     * @param conversationId 资源标识
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    @Transactional(rollbackFor = Exception.class)
    public List<UserQuestionView> pending(Long conversationId, int limit) {
        CurrentPrincipal principal = requireHuman();
        if (conversationId == null || conversationId <= 0) {
            throw badRequest("会话ID无效");
        }
        if (limit < 1 || limit > 100) {
            throw badRequest("分页数量必须在1到100之间");
        }
        LocalDateTime now = LocalDateTime.now();
        mapper.expirePending(principal.id(), conversationId, now);
        return mapper.selectPending(principal.id(), conversationId, limit).stream()
            .map(value -> UserQuestionView.from(value, jsonMapper)).toList();
    }

    /**
     * 处理{@code answer}并返回对应结果。
     *
     * @param questionId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public UserQuestionDecisionResult answer(
        String questionId,
        UserQuestionAnswerRequest request
    ) {
        CurrentPrincipal principal = requireHuman();
        AgentRuntimeUserQuestion current = owned(questionId, principal.id());
        String idempotencyKey = requiredKey(request == null ? null : request.idempotencyKey());
        List<String> selected = normalizedSelected(request == null ? null : request.selectedOptionIds());
        String selectedJson = jsonMapper.writeValueAsString(selected);
        String customInput = normalize(request == null ? null : request.customInput());
        String decisionHash = ContentHashing.sha256(
            "runtime-user-question:" + questionId + ":submitted:" + selectedJson + ":"
                + (customInput == null ? "" : customInput) + ":" + idempotencyKey
        );
        if (!"pending".equals(current.getStatus())) {
            return replayOrConflict(current, idempotencyKey, decisionHash, "submitted");
        }
        LocalDateTime now = LocalDateTime.now();
        if (!current.getExpiresAt().isAfter(now)) {
            mapper.expireOwned(current.getId(), principal.id(), now);
            throw conflict("问题已过期，无法提交回答");
        }
        validateAnswer(current, selected, customInput);
        if (mapper.submitAnswer(
            current.getId(), principal.id(), selectedJson, customInput, idempotencyKey,
            decisionHash, now
        ) != 1) {
            throw conflict("问题已被其他请求处理");
        }
        AgentRuntimeUserQuestion answered = mapper.selectOwned(questionId, principal.id());
        boolean resumed = resumeRuntime(answered, principal);
        return new UserQuestionDecisionResult(
            UserQuestionView.from(answered, jsonMapper), false, resumed
        );
    }

    /**
     * 判断{@code cel}是否满足要求。
     *
     * @param questionId 资源标识
     * @param request 请求参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public UserQuestionDecisionResult cancel(
        String questionId,
        UserQuestionCancelRequest request
    ) {
        CurrentPrincipal principal = requireHuman();
        AgentRuntimeUserQuestion current = owned(questionId, principal.id());
        String idempotencyKey = requiredKey(request == null ? null : request.idempotencyKey());
        String decisionHash = ContentHashing.sha256(
            "runtime-user-question:" + questionId + ":cancelled:" + idempotencyKey
        );
        if (!"pending".equals(current.getStatus())) {
            return replayOrConflict(current, idempotencyKey, decisionHash, "cancelled");
        }
        LocalDateTime now = LocalDateTime.now();
        if (!current.getExpiresAt().isAfter(now)) {
            mapper.expireOwned(current.getId(), principal.id(), now);
            throw conflict("问题已过期，无法取消");
        }
        if (mapper.cancelQuestion(
            current.getId(), principal.id(), idempotencyKey, decisionHash, now
        ) != 1) {
            throw conflict("问题已被其他请求处理");
        }
        AgentRuntimeUserQuestion cancelled = mapper.selectOwned(questionId, principal.id());
        boolean resumed = resumeRuntime(cancelled, principal);
        return new UserQuestionDecisionResult(
            UserQuestionView.from(cancelled, jsonMapper), false, resumed
        );
    }

    /**
     * 处理resume运行时并返回对应结果。
     *
     * @param question 追问参数
     * @param principal 当前操作主体
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean resumeRuntime(
        AgentRuntimeUserQuestion question,
        CurrentPrincipal principal
    ) {
        if (conversationTurnService == null || question == null
            || question.getConversationTurnId() == null) {
            return false;
        }
        UserQuestionView view = UserQuestionView.from(question, jsonMapper);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", "submitted".equals(question.getStatus()));
        result.put("status", question.getStatus());
        result.put("question_id", question.getQuestionId());
        result.put("selected_option_ids", view.selectedOptionIds());
        result.put("custom_input", view.customInput() == null ? "" : view.customInput());
        result.put("message", "submitted".equals(question.getStatus())
            ? "用户已回答问题" : "用户取消了问题");
        conversationTurnService.resumeFromUserQuestion(question, principal, result);
        return true;
    }

    /**
     * 处理{@code replayOrConflict}并返回对应结果。
     *
     * @param current 当前参数
     * @param idempotencyKey {@code idempotencyKey}参数
     * @param decisionHash {@code decisionHash}参数
     * @param expectedStatus 目标状态
     * @return 处理结果
     */
    private UserQuestionDecisionResult replayOrConflict(
        AgentRuntimeUserQuestion current,
        String idempotencyKey,
        String decisionHash,
        String expectedStatus
    ) {
        if (expectedStatus.equals(current.getStatus())
            && idempotencyKey.equals(current.getAnswerIdempotencyKey())
            && decisionHash.equals(current.getDecisionKeyHash())) {
            return new UserQuestionDecisionResult(UserQuestionView.from(current, jsonMapper), true);
        }
        throw conflict("问题已被处理，不能重复提交不同回答");
    }

    /**
     * 校验{@code Create}，并在条件不满足时终止处理。
     *
     * @param command 命令参数
     */
    private void validateCreate(UserQuestionCreateCommand command) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (command == null || command.ownerId() == null || command.ownerId() <= 0
            || command.conversationId() == null || command.conversationId() <= 0) {
            throw badRequest("问题必须绑定有效的用户和会话");
        }
        requiredText(command.question(), 2000, "问题内容");
        requiredKey(command.idempotencyKey());
        if (command.options() == null || command.options().size() < 2 || command.options().size() > 12) {
            throw badRequest("问题选项数量必须在2到12之间");
        }
        HashSet<String> ids = new HashSet<>();
        for (Map<String, Object> option : command.options()) {
            if (option == null) {
                throw badRequest("问题选项不能为空");
            }
            String id = requiredText(text(option.get("id")), 128, "选项ID");
            requiredText(text(option.get("label")), 500, "选项名称");
            if (!ids.add(id)) {
                throw badRequest("问题选项ID不能重复");
            }
        }
    }

    /**
     * 校验{@code Answer}，并在条件不满足时终止处理。
     *
     * @param question 追问参数
     * @param selected {@code selected}参数
     * @param customInput {@code customInput}参数
     */
    private void validateAnswer(
        AgentRuntimeUserQuestion question,
        List<String> selected,
        String customInput
    ) {
        // 以下流程逐项处理输入数据，并在遍历过程中应用必要的校验与状态更新。
        if (selected.stream().anyMatch(value -> value == null || value.isBlank())
            || selected.size() != new HashSet<>(selected).size()) {
            throw badRequest("回答选项不能为空或重复");
        }
        List<Map<String, Object>> options = jsonMapper.readValue(question.getOptionsJson(), OPTIONS);
        HashSet<String> allowed = new HashSet<>();
        for (Map<String, Object> option : options) {
            allowed.add(text(option.get("id")));
        }
        if (!allowed.containsAll(selected)) {
            throw badRequest("回答包含无效选项");
        }
        if (!question.isMultiSelect() && selected.size() > 1) {
            throw badRequest("单选问题只能选择一个选项");
        }
        if (customInput != null && !question.isAllowCustomInput()) {
            throw badRequest("当前问题不允许补充输入");
        }
        if (selected.isEmpty() && customInput == null) {
            throw badRequest("至少选择一个选项或填写补充说明");
        }
    }

    /**
     * 处理{@code normalizedSelected}并返回对应结果。
     *
     * @param values {@code values}参数
     * @return 符合条件的数据集合
     */
    private List<String> normalizedSelected(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().map(value -> requiredText(value, 128, "选项ID")).toList();
    }

    /**
     * 处理{@code sameCreate}并返回对应结果。
     *
     * @param left {@code left}参数
     * @param right {@code right}参数
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean sameCreate(AgentRuntimeUserQuestion left, AgentRuntimeUserQuestion right) {
        return left.getOwnerId().equals(right.getOwnerId())
            && left.getConversationId().equals(right.getConversationId())
            && left.getIdempotencyKey().equals(right.getIdempotencyKey());
    }

    /**
     * 处理{@code owned}并返回对应结果。
     *
     * @param questionId 资源标识
     * @param ownerId 资源标识
     * @return 处理结果
     */
    private AgentRuntimeUserQuestion owned(String questionId, Long ownerId) {
        String key = requiredQuestionId(questionId);
        AgentRuntimeUserQuestion value = mapper.selectOwned(key, ownerId);
        if (value == null) {
            throw new ServiceException("问题不存在或无权访问", HttpStatus.NOT_FOUND);
        }
        return value;
    }

    /**
     * 处理{@code expireIfNeeded}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param ownerId 资源标识
     * @return 处理结果
     */
    private AgentRuntimeUserQuestion expireIfNeeded(
        AgentRuntimeUserQuestion value,
        Long ownerId
    ) {
        if ("pending".equals(value.getStatus()) && !value.getExpiresAt().isAfter(LocalDateTime.now())) {
            mapper.expireOwned(value.getId(), ownerId, LocalDateTime.now());
            AgentRuntimeUserQuestion expired = mapper.selectOwned(value.getQuestionId(), ownerId);
            return expired == null ? value : expired;
        }
        return value;
    }

    /**
     * 校验{@code Human}，并在条件不满足时终止处理。
     *
     * @return 处理结果
     */
    private CurrentPrincipal requireHuman() {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        if (principal == null) {
            throw new ServiceException("未登录", HttpStatus.UNAUTHORIZED);
        }
        if (!principal.isHuman()) {
            throw new ServiceException("服务账号不能处理用户提问", HttpStatus.FORBIDDEN);
        }
        return principal;
    }

    /**
     * 校验追问Id，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String requiredQuestionId(String value) {
        String key = normalize(value);
        if (key == null || key.length() > 128) {
            throw badRequest("问题标识无效");
        }
        return key;
    }

    /**
     * 校验{@code dKey}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String requiredKey(String value) {
        String key = requiredText(value, 128, "幂等键");
        return key;
    }

    /**
     * 校验{@code dText}，并在条件不满足时终止处理。
     *
     * @param value {@code value}参数
     * @param maxLength {@code maxLength}参数
     * @param field {@code field}参数
     * @return 处理结果
     */
    private String requiredText(String value, int maxLength, String field) {
        String normalized = normalize(value);
        if (normalized == null || normalized.length() > maxLength || normalized.indexOf('\0') >= 0) {
            throw badRequest(field + "无效");
        }
        return normalized;
    }

    /**
     * 处理{@code text}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String text(Object value) {
        return value == null ? null : normalize(String.valueOf(value));
    }

    /**
     * 处理{@code normalize}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * 处理{@code badRequest}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException badRequest(String message) {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }

    /**
     * 处理{@code conflict}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException conflict(String message) {
        return new ServiceException(message, HttpStatus.CONFLICT);
    }
}
