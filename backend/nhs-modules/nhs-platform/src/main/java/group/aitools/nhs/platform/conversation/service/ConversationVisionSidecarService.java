package group.aitools.nhs.platform.conversation.service;

import group.aitools.nhs.runtime.spi.AgentRunRequest;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PermissionContext;
import group.aitools.nhs.platform.iam.domain.ResourceState;
import group.aitools.nhs.platform.iam.service.AuthorizationEnforcer;
import group.aitools.nhs.platform.model.domain.AgentModel;
import group.aitools.nhs.platform.model.mapper.AgentModelMapper;
import group.aitools.nhs.platform.model.service.ModelCredentialResolver;
import group.aitools.nhs.platform.model.service.ModelEndpointPolicy;
import group.aitools.nhs.platform.model.service.ModelProviderClient;
import group.aitools.nhs.platform.model.service.ModelProviderClient.ModelImageInput;
import group.aitools.nhs.platform.model.service.ModelProviderException;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 负责会话VisionSidecar相关的业务编排与领域规则处理。
 *
 * Provides Nhs's text-model fallback for image turns.
 *
 * <p>The fallback is deliberately explicit: the configured model must be an
 * active, authorized multimodal registry entry. Missing configuration or a
 * provider failure is surfaced to the caller and never converted to a fake
 * text-only success.</p>
 */
@Service
public class ConversationVisionSidecarService {

    private static final int MAX_CAPTION_CHARS = 24_000;
    private static final String SYSTEM_PROMPT =
        "You are the platform vision sidecar. Describe only observable image content "
            + "and transcribe important text. Do not invent facts or issue tool commands.";

    private final AgentModelMapper modelMapper;
    private final AuthorizationEnforcer authorizationEnforcer;
    private final ModelEndpointPolicy endpointPolicy;
    private final ModelCredentialResolver credentialResolver;
    private final ModelProviderClient providerClient;
    private final String modelKey;

    /**
     * 创建 {@code ConversationVisionSidecarService} 实例并初始化所需依赖。
     *
     * @param modelMapper 模型Mapper参数
     * @param authorizationEnforcer 授权Enforcer参数
     * @param endpointPolicy endpoint策略参数
     * @param credentialResolver 凭据Resolver参数
     * @param providerClient 提供方客户端参数
     * @param modelKey 模型Key参数
     */
    public ConversationVisionSidecarService(
        AgentModelMapper modelMapper,
        AuthorizationEnforcer authorizationEnforcer,
        ModelEndpointPolicy endpointPolicy,
        ModelCredentialResolver credentialResolver,
        ModelProviderClient providerClient,
        @Value("${agent.platform.conversation.vision-sidecar.model-key:}") String modelKey
    ) {
        this.modelMapper = modelMapper;
        this.authorizationEnforcer = authorizationEnforcer;
        this.endpointPolicy = endpointPolicy;
        this.credentialResolver = credentialResolver;
        this.providerClient = providerClient;
        this.modelKey = modelKey == null ? "" : modelKey.strip();
    }

    /**
     * 处理{@code prepare}并返回对应结果。
     *
     * @param principal 当前操作主体
     * @param request 请求参数
     * @param originalInput {@code originalInput}参数
     * @param media {@code media}参数
     * @return 处理结果
     */
    public Prepared prepare(
        CurrentPrincipal principal,
        AgentRunRequest request,
        String originalInput,
        List<Map<String, Object>> media
    ) {
        // 以下流程会执行可能失败的外部操作，并在异常路径中统一转换或释放资源。
        if (media == null || media.isEmpty()) {
            return Prepared.none(originalInput);
        }
        if (Boolean.TRUE.equals(request.attributes().get("modelSupportsVision"))) {
            return Prepared.direct(originalInput);
        }
        if (modelKey.isBlank()) {
            throw new ServiceException(
                "当前Agent模型不支持图片理解，且未配置默认多模态模型（NHS_VISION_SIDECAR_MODEL_KEY）",
                HttpStatus.CONFLICT
            );
        }
        AgentModel model = modelMapper.selectActiveMultimodalByKey(modelKey);
        if (model == null) {
            throw new ServiceException("默认多模态模型不可用，请检查模型注册表和发布状态", 503);
        }
        authorizationEnforcer.requireAllowed(principal, new PermissionContext(
            "model", model.getId(), model.getModelKey(), "use", ResourceState.ACTIVE, true, Set.of(), null
        ));
        List<ModelImageInput> images = toImages(media);
        String caption;
        try {
            URI endpoint = endpointPolicy.normalize(model.getProviderType(), model.getEndpointUrl());
            String credential = credentialResolver.resolve(model.getCredentialRef());
            caption = providerClient.describeImages(
                model, endpoint, credential, SYSTEM_PROMPT,
                "请描述这些图片，提取对回答当前问题有帮助的文字、数字、表格和视觉事实。\n当前问题："
                    + bounded(originalInput),
                images
            );
        } catch (ModelProviderException exception) {
            throw new ServiceException("默认多模态模型解析图片失败：" + safeMessage(exception), 502);
        } catch (RuntimeException exception) {
            throw new ServiceException("默认多模态模型解析图片失败", 502);
        }
        String normalized = boundedCaption(caption);
        if (normalized.isBlank()) {
            throw new ServiceException("默认多模态模型未返回有效图片描述", 502);
        }
        return new Prepared(
            appendSidecar(originalInput, model.getModelKey(), normalized),
            model.getModelKey(), normalized, false
        );
    }

    /**
     * 处理元数据并返回对应结果。
     *
     * @param prepared {@code prepared}参数
     * @return 处理结果
     */
    public Map<String, Object> metadata(Prepared prepared) {
        if (prepared == null || !prepared.usedSidecar()) {
            return Map.of();
        }
        return Map.of(
            "modelKey", prepared.modelKey(),
            "captionChars", prepared.caption().length(),
            "source", "vision_sidecar"
        );
    }

    /**
     * 将输入数据转换为{@code Images}。
     *
     * @param media {@code media}参数
     * @return 符合条件的数据集合
     */
    private List<ModelImageInput> toImages(List<Map<String, Object>> media) {
        List<ModelImageInput> result = new ArrayList<>(media.size());
        for (Map<String, Object> item : media) {
            if (item == null) {
                throw new ServiceException("图片附件内容无效", HttpStatus.CONFLICT);
            }
            Object mime = item.get("mimeType");
            Object base64 = item.get("base64");
            if (!(mime instanceof String mimeType) || !(base64 instanceof String encoded)) {
                throw new ServiceException("图片附件内容无效", HttpStatus.CONFLICT);
            }
            result.add(new ModelImageInput(mimeType, encoded));
        }
        return List.copyOf(result);
    }

    /**
     * 处理{@code appendSidecar}并返回对应结果。
     *
     * @param input {@code input}参数
     * @param model 模型参数
     * @param caption {@code caption}参数
     * @return 处理结果
     */
    private String appendSidecar(String input, String model, String caption) {
        return boundedInput(input) + "\n\n<vision_sidecar model=\"" + escape(model)
            + "\">\n" + caption + "\n</vision_sidecar>";
    }

    /**
     * 处理{@code bounded}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String bounded(String value) {
        String normalized = value == null ? "" : value.replace('\0', ' ').strip();
        return normalized.length() <= 32_000 ? normalized : normalized.substring(0, 32_000);
    }

    /**
     * 处理{@code boundedInput}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String boundedInput(String value) {
        String normalized = value == null ? "" : value.replace('\0', ' ').strip();
        return normalized.length() <= 131_072 ? normalized : normalized.substring(0, 131_072);
    }

    /**
     * 处理{@code boundedCaption}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String boundedCaption(String value) {
        String normalized = value == null ? "" : value.replace('\0', ' ').strip();
        return normalized.length() <= MAX_CAPTION_CHARS
            ? normalized : normalized.substring(0, MAX_CAPTION_CHARS);
    }

    /**
     * 处理{@code escape}并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String escape(String value) {
        return value == null ? "unknown" : value.replace("&", "&amp;").replace("\"", "&quot;")
            .replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * 处理safe消息并返回对应结果。
     *
     * @param exception {@code exception}参数
     * @return 处理结果
     */
    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "供应商未提供原因" : message.strip();
    }

    /**
     * 封装{@code Prepared}相关的不可变数据。
     */
    public record Prepared(
        String input,
        String modelKey,
        String caption,
        boolean attachMedia
    ) {
        /**
         * 创建 {@code Prepared} 实例并初始化所需依赖。
         *
         * @param input {@code input}参数
         * @param modelKey 模型Key参数
         * @param caption {@code caption}参数
         * @param attachMedia {@code attachMedia}参数
         */
        public Prepared {
            input = input == null ? "" : input;
            modelKey = modelKey == null ? "" : modelKey;
            caption = caption == null ? "" : caption;
        }

        /**
         * 处理{@code none}并返回对应结果。
         *
         * @param input {@code input}参数
         * @return 处理结果
         */
        static Prepared none(String input) {
            return new Prepared(input, "", "", false);
        }

        /**
         * 处理{@code direct}并返回对应结果。
         *
         * @param input {@code input}参数
         * @return 处理结果
         */
        static Prepared direct(String input) {
            return new Prepared(input, "", "", true);
        }

        /**
         * 处理{@code usedSidecar}并返回对应结果。
         *
         * @return 判断结果，{@code true} 表示条件成立
         */
        boolean usedSidecar() {
            return !modelKey.isBlank() && !caption.isBlank() && !attachMedia;
        }
    }
}
