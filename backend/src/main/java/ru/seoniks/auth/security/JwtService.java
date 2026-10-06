package ru.seoniks.auth.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Service;
import ru.seoniks.auth.config.AppProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private final RSAPrivateKey privateKey;
    private final RSAPublicKey publicKey;
    private final Duration accessTokenTtl;

    public JwtService(AppProperties props) {
        this.privateKey = readPrivateKey(props.jwt().privateKeyPath());
        this.publicKey = readPublicKey(props.jwt().publicKeyPath());
        this.accessTokenTtl = props.jwt().accessTokenTtl();
    }

    public String issueAccessToken(UUID userId, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("role", role)
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenTtl)))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    public Claims parseAccessToken(String token) {
        return Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private static RSAPrivateKey readPrivateKey(Path path) {
        try {
            byte[] der = pemToDer(path, "PRIVATE KEY");
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
            return (RSAPrivateKey) rsaFactory().generatePrivate(spec);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось прочитать приватный ключ: " + path, e);
        }
    }

    private static RSAPublicKey readPublicKey(Path path) {
        try {
            byte[] der = pemToDer(path, "PUBLIC KEY");
            X509EncodedKeySpec spec = new X509EncodedKeySpec(der);
            return (RSAPublicKey) rsaFactory().generatePublic(spec);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось прочитать публичный ключ: " + path, e);
        }
    }

    private static KeyFactory rsaFactory() throws NoSuchAlgorithmException {
        return KeyFactory.getInstance("RSA");
    }

    private static byte[] pemToDer(Path path, String label) throws IOException {
        String pem = Files.readString(path);
        String base64 = pem
                .replace("-----BEGIN " + label + "-----", "")
                .replace("-----END " + label + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }
}