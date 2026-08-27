package group.aitools.nhs.platform.model.service;

import group.aitools.nhs.platform.model.domain.AgentModel;
import group.aitools.nhs.platform.model.web.ModelConnectionView;
import group.aitools.nhs.platform.model.web.ModelOptionView;

import java.net.URI;
import java.util.List;

/**
 * 处理{@code discover}并返回对应结果。
 *
 * 定义模型提供方相关能力的服务契约。
 * Outbound provider operations used by model administration. */
public interface ModelProviderClient {

    List<ModelOptionView> discover(URI endpoint, String credential);

    /**
     * 处理{@code test}并返回对应结果。
     *
     * @param model 模型参数
     * @param endpoint {@code endpoint}参数
     * @param credential 凭据参数
     * @return 处理结果
     */
    ModelConnectionView test(AgentModel model, URI endpoint, String credential);

    /**
 * 处理{@code describeImages}并返回对应结果。
 * Executes a bounded image-to-text request for the conversation vision sidecar. */
    String describeImages(
        AgentModel model,
        URI endpoint,
        String credential,
        String systemPrompt,
        String userPrompt,
        List<ModelImageInput> images
    );

    /**
     * 封装模型ImageInput相关的不可变数据。
     */
    record ModelImageInput(String mimeType, String base64) {
    }
}
