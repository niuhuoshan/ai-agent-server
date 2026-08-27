package group.aitools.nhs.platform.nhs.portal.chatbi;

import group.aitools.nhs.platform.model.domain.AgentModel;
import group.aitools.nhs.platform.model.mapper.AgentModelMapper;
import group.aitools.nhs.platform.model.service.HttpModelProviderClient;
import group.aitools.nhs.platform.model.service.ModelCredentialResolver;
import group.aitools.nhs.platform.model.service.ModelEndpointPolicy;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;

/**
 * 表示门户对话BI模型Gateway相关的领域对象。
 * Resolves one active chat model and exposes a bounded completion call to portal services. */
@Component
public class PortalChatBIModelGateway {

    private final AgentModelMapper modelMapper;
    private final ModelEndpointPolicy endpointPolicy;
    private final ModelCredentialResolver credentialResolver;
    private final HttpModelProviderClient modelClient;

    public PortalChatBIModelGateway(
        AgentModelMapper modelMapper,
        ModelEndpointPolicy endpointPolicy,
        ModelCredentialResolver credentialResolver,
        HttpModelProviderClient modelClient
    ) {
        this.modelMapper = modelMapper;
        this.endpointPolicy = endpointPolicy;
        this.credentialResolver = credentialResolver;
        this.modelClient = modelClient;
    }

    /**
     * 处理{@code complete}并返回对应结果。
     *
     * @param systemPrompt 系统提示词参数
     * @param userPrompt 用户提示词参数
     * @return 处理结果
     */
    public Completion complete(String systemPrompt, String userPrompt) {
        List<AgentModel> models = modelMapper.selectModels("chat", null, null, false, 1);
        if (models.isEmpty()) {
            throw unavailable("未配置可用的对话模型，ChatBI 自然语言查询当前不可用");
        }
        AgentModel model = models.get(0);
        try {
            URI endpoint = endpointPolicy.normalize(model.getProviderType(), model.getEndpointUrl());
            String credential = credentialResolver.resolve(model.getCredentialRef());
            String content = modelClient.complete(
                model, endpoint, credential, systemPrompt, userPrompt
            );
            return new Completion(model.getId(), content);
        } catch (ServiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable("ChatBI 模型服务不可用：" + safeReason(exception));
        }
    }

    /**
     * 处理{@code safeReason}并返回对应结果。
     *
     * @param exception {@code exception}参数
     * @return 处理结果
     */
    private String safeReason(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "模型调用失败";
        }
        String normalized = message.replaceAll("[\\r\\n]+", " ").strip();
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200);
    }

    /**
     * 处理{@code unavailable}并返回对应结果。
     *
     * @param message 待处理内容
     * @return 处理结果
     */
    private ServiceException unavailable(String message) {
        return new ServiceException(message, 503);
    }

    /**
     * 封装{@code Completion}相关的不可变数据。
     */
    public record Completion(Long modelId, String content) {
    }
}
