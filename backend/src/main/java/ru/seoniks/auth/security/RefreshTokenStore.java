package ru.seoniks.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import ru.seoniks.auth.config.AppProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

// В Redis лежит SHA-256 хеш токена, а не сам токен; каждый токен одноразовый.
// Повторное предъявление использованного токена отзывает всю семью сессий устройства.
@Component
public class RefreshTokenStore {

    public enum RotateStatus { OK, INVALID, REUSE }

    public record RotateResult(RotateStatus status, UUID userId, String newToken) {
        static RotateResult ok(UUID userId, String newToken) {
            return new RotateResult(RotateStatus.OK, userId, newToken);
        }
        static RotateResult invalid() {
            return new RotateResult(RotateStatus.INVALID, null, null);
        }
        static RotateResult reuse() {
            return new RotateResult(RotateStatus.REUSE, null, null);
        }
    }

    private record RefreshRecord(UUID userId, String family, Instant expiresAt) {}

    private static final String RT = "rt:";
    private static final String FAM = "fam:";
    private static final String USED = "used:";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Duration ttl;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenStore(StringRedisTemplate redis,
                             ObjectMapper mapper,
                             AppProperties props) {
        this.redis = redis;
        this.mapper = mapper;
        this.ttl = props.refresh().ttl();
    }

    public String create(UUID userId) {
        String family = UUID.randomUUID().toString();
        String token = randomToken();
        put(userId, family, token);
        return token;
    }

    // Чтение и удаление токена не атомарны, поэтому два параллельных обмена одним
    // токеном теоретически могут пройти оба. Клиент так не делает: перехватчик Axios
    // держит один общий промис на все 401. Атомарность через Lua понадобится, только
    // если refresh начнут обслуживать несколько инстансов.
    public RotateResult rotate(String token) {
        String hash = sha256(token);
        String json = redis.opsForValue().get(RT + hash);

        if (json == null) {
            String family = redis.opsForValue().get(USED + hash);
            if (family != null) {
                revokeFamily(family);
                return RotateResult.reuse();
            }
            return RotateResult.invalid();
        }

        RefreshRecord record = read(json);

        redis.delete(RT + hash);
        redis.opsForSet().remove(FAM + record.family(), hash);
        redis.opsForValue().set(USED + hash, record.family(), ttl);

        String newToken = randomToken();
        put(record.userId(), record.family(), newToken);
        return RotateResult.ok(record.userId(), newToken);
    }

    public void revoke(String token) {
        if (token == null) {
            return;
        }
        String hash = sha256(token);
        String json = redis.opsForValue().get(RT + hash);
        if (json != null) {
            RefreshRecord record = read(json);
            redis.delete(RT + hash);
            redis.opsForSet().remove(FAM + record.family(), hash);
        }
    }

    private void put(UUID userId, String family, String token) {
        String hash = sha256(token);
        try {
            String json = mapper.writeValueAsString(
                    new RefreshRecord(userId, family, Instant.now().plus(ttl)));
            redis.opsForValue().set(RT + hash, json, ttl);
            redis.opsForSet().add(FAM + family, hash);
            redis.expire(FAM + family, ttl);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось сохранить refresh-токен", e);
        }
    }

    private void revokeFamily(String family) {
        Set<String> hashes = redis.opsForSet().members(FAM + family);
        if (hashes != null && !hashes.isEmpty()) {
            redis.delete(hashes.stream().map(h -> RT + h).toList());
        }
        redis.delete(FAM + family);
    }

    private RefreshRecord read(String json) {
        try {
            return mapper.readValue(json, RefreshRecord.class);
        } catch (Exception e) {
            throw new IllegalStateException("Повреждённая запись refresh-токена", e);
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }
}