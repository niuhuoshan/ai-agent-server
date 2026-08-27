package group.aitools.nhs.platform.data.service;

import group.aitools.nhs.platform.data.domain.AgentDataColumn;
import group.aitools.nhs.platform.model.domain.AgentModel;
import group.aitools.nhs.platform.model.mapper.AgentModelMapper;
import group.aitools.nhs.platform.model.service.HttpModelProviderClient;
import group.aitools.nhs.platform.model.service.ModelCredentialResolver;
import group.aitools.nhs.platform.model.service.ModelEndpointPolicy;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class MetadataProfileModelGatewayTest {

    private final AgentModelMapper modelMapper = mock(AgentModelMapper.class);
    private final ModelEndpointPolicy endpointPolicy = mock(ModelEndpointPolicy.class);
    private final ModelCredentialResolver credentialResolver = mock(ModelCredentialResolver.class);
    private final HttpModelProviderClient modelClient = mock(HttpModelProviderClient.class);
    private final MetadataProfileModelGateway gateway = new MetadataProfileModelGateway(
        modelMapper, endpointPolicy, credentialResolver, modelClient, JsonMapper.builder().build()
    );

    @Test
    void parsesGroundedTableAndEveryColumnSemanticFromConfiguredProvider() {
        AgentModel model = model();
        URI endpoint = URI.create("https://models.example.test/v1/");
        when(modelMapper.selectModels("chat", null, null, false, 1)).thenReturn(List.of(model));
        when(endpointPolicy.normalize("openai_compatible", model.getEndpointUrl())).thenReturn(endpoint);
        when(credentialResolver.resolve("env://MODEL_TOKEN")).thenReturn("secret");
        when(modelClient.complete(
            eq(model), eq(endpoint), eq("secret"), anyString(), anyString(), eq(4096)
        )).thenReturn("""
            {"table_term":"订单明细","table_description":"记录客户订单及金额",\
             "tags":["交易","订单"],"confidence_score":92,\
             "confidence_reason":"表名、主键和样例一致",\
             "temporary_classification":"business",\
             "columns":[\
               {"physical_name":"id","term":"订单ID","description":"订单唯一标识"},\
               {"physical_name":"amount","term":"订单金额","description":"订单含税金额"}\
             ]}
            """);

        var result = gateway.analyze(
            "public.orders", "CREATE TABLE public.orders (...)", "[]",
            List.of(column(1L, "id", "bigint"), column(2L, "amount", "decimal"))
        );

        assertEquals("订单明细", result.tableTerm());
        assertEquals(92, result.confidenceScore());
        assertEquals(List.of("交易", "订单"), result.tags());
        assertEquals("订单金额", result.columns().get(1).term());
    }

    @Test
    void rejectsProviderOutputThatOmitsOneCatalogColumn() {
        AgentModel model = model();
        URI endpoint = URI.create("https://models.example.test/v1/");
        when(modelMapper.selectModels("chat", null, null, false, 1)).thenReturn(List.of(model));
        when(endpointPolicy.normalize("openai_compatible", model.getEndpointUrl())).thenReturn(endpoint);
        when(credentialResolver.resolve("env://MODEL_TOKEN")).thenReturn("secret");
        when(modelClient.complete(
            eq(model), eq(endpoint), eq("secret"), anyString(), anyString(), eq(4096)
        )).thenReturn("""
            {"table_term":"订单","table_description":"订单数据","tags":["订单"],\
             "confidence_score":80,"confidence_reason":"结构明确",\
             "temporary_classification":"business",\
             "columns":[{"physical_name":"id","term":"ID","description":"主键"}]}
            """);

        ServiceException failure = assertThrows(ServiceException.class, () -> gateway.analyze(
            "public.orders", "CREATE TABLE public.orders (...)", "[]",
            List.of(column(1L, "id", "bigint"), column(2L, "amount", "decimal"))
        ));

        assertTrue(failure.getMessage().contains("遗漏字段"));
    }

    private AgentModel model() {
        AgentModel model = new AgentModel();
        model.setId(10L);
        model.setDisplayName("metadata-model");
        model.setProviderType("openai_compatible");
        model.setModelName("gpt-test");
        model.setModelType("chat");
        model.setEndpointUrl("https://models.example.test/v1");
        model.setCredentialRef("env://MODEL_TOKEN");
        model.setMaxOutputTokens(4096);
        model.setStatus("active");
        return model;
    }

    private AgentDataColumn column(Long id, String name, String type) {
        AgentDataColumn column = new AgentDataColumn();
        column.setId(id);
        column.setPhysicalName(name);
        column.setDisplayName(name);
        column.setDataType(type);
        column.setIsPrimary("id".equals(name));
        column.setIsSensitive(false);
        column.setStatus("active");
        column.setMetadataPresent(true);
        return column;
    }
}
