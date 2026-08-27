package group.aitools.nhs.test;

import group.aitools.nhs.common.encrypt.utils.EncryptUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class ApiDecryptConfigurationTest {

    private static final Pattern PRIVATE_KEY_DEFAULT = Pattern.compile(
        "^\\$\\{API_DECRYPT_PRIVATE_KEY:(.+)}$"
    );

    @Test
    void packagedDefaultRsaKeyPairIsValidAndMatches() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
            "application", new ClassPathResource("application.yml")
        );
        String publicKey = property(sources, "api-decrypt.publicKey");
        String privateKeyExpression = property(sources, "api-decrypt.privateKey");
        Matcher matcher = PRIVATE_KEY_DEFAULT.matcher(privateKeyExpression);

        assertTrue(matcher.matches(), "API_DECRYPT_PRIVATE_KEY must provide a valid development default");
        String privateKey = matcher.group(1);
        EncryptUtils.validateRsaPublicKey(publicKey);
        EncryptUtils.validateRsaPrivateKey(privateKey);

        String plaintext = "nhs-rsa-healthcheck";
        assertEquals(plaintext, EncryptUtils.decryptByRsa(
            EncryptUtils.encryptByRsa(plaintext, publicKey), privateKey
        ));
    }

    @Test
    void truncatedConfiguredPublicKeyFailsClosed() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
            "application", new ClassPathResource("application.yml")
        );
        String publicKey = property(sources, "api-decrypt.publicKey");

        assertThrows(IllegalArgumentException.class,
            () -> EncryptUtils.validateRsaPublicKey(publicKey.substring(0, publicKey.length() - 1)));
    }

    private String property(List<PropertySource<?>> sources, String name) {
        Object value = sources.stream()
            .map(source -> source.getProperty(name))
            .filter(candidate -> candidate != null)
            .findFirst()
            .orElse(null);
        assertNotNull(value, () -> "Missing property " + name);
        return value.toString();
    }
}
