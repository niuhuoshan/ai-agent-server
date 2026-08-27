package group.aitools.nhs.platform.identity.web;

/**
 * 封装Issued接口凭据相关的不可变数据。
 * One-time credential response; secret is never available through any later endpoint. */
public record IssuedApiCredentialView(ApiCredentialView credential, String secret) {
}
