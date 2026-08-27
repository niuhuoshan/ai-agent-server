package group.aitools.nhs.platform.data.service;

public interface DataCredentialResolver {

    DataCredential resolve(String credentialRef);
}
