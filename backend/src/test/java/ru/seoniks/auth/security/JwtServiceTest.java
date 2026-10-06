package ru.seoniks.auth.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.seoniks.auth.config.AppProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    @TempDir
    Path dir;

    private JwtService service;
    private JwtService serviceOnOtherKey;
    private KeyPair primary;

    @BeforeEach
    void setUp() throws Exception {
        primary = rsaKeyPair();
        KeyPair secondary = rsaKeyPair();

        service = newService(primary, Duration.ofMinutes(15));
        serviceOnOtherKey = newService(secondary, Duration.ofMinutes(15));
    }

    @Test
    void issuedTokenParsesBackToSameSubjectAndRole() {
        UUID userId = UUID.randomUUID();

        String token = service.issueAccessToken(userId, "ADMIN");
        Claims claims = service.parseAccessToken(token);

        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.get("role", String.class)).isEqualTo("ADMIN");
        assertThat(claims.getId()).isNotBlank();
        assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());
    }

    @Test
    void tamperedSignatureIsRejected() {
        String token = service.issueAccessToken(UUID.randomUUID(), "USER");

        assertThatThrownBy(() -> service.parseAccessToken(forgeSignature(token)))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        JwtService shortLived = newService(primary, Duration.ofMinutes(-5));
        String token = shortLived.issueAccessToken(UUID.randomUUID(), "USER");

        assertThatThrownBy(() -> service.parseAccessToken(token))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        String foreign = serviceOnOtherKey.issueAccessToken(UUID.randomUUID(), "ADMIN");

        assertThatThrownBy(() -> service.parseAccessToken(foreign))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void malformedTokenIsRejected() {
        assertThatThrownBy(() -> service.parseAccessToken("не.jwt"))
                .isInstanceOf(JwtException.class);
    }

    private JwtService newService(KeyPair pair, Duration ttl) throws IOException {
        Path privateKey = writePem("private-" + ttl.toMillis() + "-" + pair.hashCode() + ".pem",
                "PRIVATE KEY", pair.getPrivate().getEncoded());
        Path publicKey = writePem("public-" + ttl.toMillis() + "-" + pair.hashCode() + ".pem",
                "PUBLIC KEY", pair.getPublic().getEncoded());
        return new JwtService(new AppProperties(
                new AppProperties.Jwt(privateKey, publicKey, ttl), null, null, null));
    }

    private Path writePem(String name, String label, byte[] der) throws IOException {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return Files.writeString(dir.resolve(name),
                "-----BEGIN " + label + "-----\n" + base64 + "\n-----END " + label + "-----\n");
    }

    private static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    /** Меняет один байт подписи, оставляя токен корректным base64url. */
    private static String forgeSignature(String token) {
        int dot = token.lastIndexOf('.');
        byte[] signature = Base64.getUrlDecoder().decode(token.substring(dot + 1));
        signature[0] = (byte) (signature[0] ^ 0xFF);
        String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        return token.substring(0, dot + 1) + forged;
    }
}
