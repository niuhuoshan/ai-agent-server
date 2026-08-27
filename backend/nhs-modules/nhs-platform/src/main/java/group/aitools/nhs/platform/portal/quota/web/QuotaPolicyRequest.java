package group.aitools.nhs.platform.portal.quota.web;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.Min;
/**
 * 封装Quota策略相关的不可变数据。
 * Request used to create or replace a monthly quota policy. */
public record QuotaPolicyRequest(
    Boolean enabled,
    @JsonAlias("limit_tokens") @Min(0) Long limitTokens
) {
}
