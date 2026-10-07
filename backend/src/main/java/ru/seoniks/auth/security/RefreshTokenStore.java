package ru.seoniks.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import ru.seoniks.auth.config.AppProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

// В Redis лежит SHA-256 хеш токена, а не сам токен; каждый токен одноразовый.
// Повторное предъявление использованного токена отзывает всю семью сессий устройства.
@Component
public class RefreshTokenStore {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenStore.class);
    private static final int MAX_USER_AGENT = 256;

    public enum RotateStatus { OK, INVALID, REUSE }

    public record RotateResult(RotateStatus status, UUID userId, String newToken, String familyId) {
        static RotateResult ok(UUID userId, String newToken, String familyId) {
            return new RotateResult(RotateStatus.OK, userId, newToken, familyId);
        }
        static RotateResult invalid() {
            return new RotateResult(RotateStatus.INVALID, null, null, null);
        }
        static RotateResult reuse() {
            return new RotateResult(RotateStatus.REUSE, null, null, null);
        }
    }

    public record IssuedRefresh(String token, String familyId) {}

    // Семья сессий: одна цепочка ротаций refresh-токена одного устройства.
    public record SessionInfo(String familyId,
                              UUID userId,
                              String userEmail,
                              Instant createdAt,
                              Instant lastUsedAt,
                              String ip,
                              String userAgent) {}

    private record RefreshRecord(UUID userId, String family, Instant expiresAt) {}

    private static final String RT = "rt:";
    private static final String FAM = "fam:";
    private static final String USED = "used:";
    private static final String SES = "ses:";
    private static final String USR = "usr:";
    private static final String SES_INDEX = "ses:index";
    private static final String AT = "at:";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Duration ttl;
    private final Duration accessTokenTtl;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenStore(StringRedisTemplate redis,
                             ObjectMapper mapper,
                             AppProperties props) {
        this.redis = redis;
        this.mapper = mapper;
        this.ttl = props.refresh().ttl();
        this.accessTokenTtl = props.jwt() == null || props.jwt().accessTokenTtl() == null
                ? Duration.ofMinutes(15)
                : props.jwt().accessTokenTtl();
    }

    public IssuedRefresh create(UUID userId, String userEmail, String ip, String userAgent) {
        String family = UUID.randomUUID().toString();
        String token = randomToken();
        Instant now = Instant.now();
        put(userId, family, token);
        writeSession(family, userId, userEmail, now, now, ip, userAgent);
        return new IssuedRefresh(token, family);
    }

    // Access-токен связывается с семьёй refresh-токена, которая его выдала.
    // Связка нужна потому, что refresh-cookie имеет узкий Path=/api/auth и не
    // приходит на /api/me/sessions, а определить «текущую» сессию иначе нельзя.
    // Живёт столько же, сколько сам access-токен.
    public void bindAccessToken(String jti, String familyId) {
        if (jti == null || familyId == null) {
            return;
        }
        redis.opsForValue().set(AT + jti, familyId, accessTokenTtl);
    }

    public String findFamilyByAccessTokenId(String jti) {
        return jti == null ? null : redis.opsForValue().get(AT + jti);
    }

    // Чтение и удаление токена не атомарны, поэтому два параллельных обмена одним
    // токеном теоретически могут пройти оба. Клиент так не делает: перехватчик Axios
    // держит один общий промис на все 401. Атомарность через Lua понадобится, только
    // если refresh начнут обслуживать несколько инстансов.
    public RotateResult rotate(String token, String ip, String userAgent) {
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
        touchSession(record, ip, userAgent);
        return RotateResult.ok(record.userId(), newToken, record.family());
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
            dropFamilyIfEmpty(record.family());
        }
    }

    /** Семья токена без его потребления: нужно, чтобы отличить текущую сессию от прочих. */
    public String findFamilyByToken(String token) {
        if (token == null) {
            return null;
        }
        String json = redis.opsForValue().get(RT + sha256(token));
        return json == null ? null : read(json).family();
    }

    public SessionInfo findSession(String familyId) {
        if (familyId == null) {
            return null;
        }
        String json = redis.opsForValue().get(SES + familyId);
        return json == null ? null : readSession(json);
    }

    public List<SessionInfo> listSessions() {
        Set<String> families = redis.opsForSet().members(SES_INDEX);
        if (families == null || families.isEmpty()) {
            return List.of();
        }
        List<SessionInfo> sessions = new ArrayList<>();
        List<String> stale = new ArrayList<>();
        for (String family : families) {
            String json = redis.opsForValue().get(SES + family);
            if (json == null) {
                stale.add(family);
                continue;
            }
            sessions.add(readSession(json));
        }
        if (!stale.isEmpty()) {
            redis.opsForSet().remove(SES_INDEX, stale.toArray(new String[0]));
        }
        return sorted(sessions);
    }

    public List<SessionInfo> listSessionsForUser(UUID userId) {
        Set<String> families = redis.opsForSet().members(USR + userId);
        if (families == null || families.isEmpty()) {
            return List.of();
        }
        List<SessionInfo> sessions = new ArrayList<>();
        List<String> stale = new ArrayList<>();
        for (String family : families) {
            String json = redis.opsForValue().get(SES + family);
            if (json == null) {
                stale.add(family);
                continue;
            }
            sessions.add(readSession(json));
        }
        if (!stale.isEmpty()) {
            redis.opsForSet().remove(USR + userId, stale.toArray(new String[0]));
        }
        return sorted(sessions);
    }

    /** Обрывает одну семью. Возвращает true, если семья существовала. */
    public boolean revokeFamilyById(String familyId) {
        if (familyId == null) {
            return false;
        }
        boolean existed = Boolean.TRUE.equals(redis.hasKey(SES + familyId))
                || Boolean.TRUE.equals(redis.hasKey(FAM + familyId));
        revokeFamily(familyId);
        return existed;
    }

    public int revokeAllForUser(UUID userId) {
        return revokeAllForUserExcept(userId, null);
    }

    public int revokeAllForUserExcept(UUID userId, String keepFamilyId) {
        Set<String> families = redis.opsForSet().members(USR + userId);
        if (families == null || families.isEmpty()) {
            return 0;
        }
        int revoked = 0;
        for (String family : families) {
            if (family.equals(keepFamilyId)) {
                continue;
            }
            if (revokeFamilyById(family)) {
                revoked++;
            }
        }
        if (keepFamilyId == null) {
            redis.delete(USR + userId);
        }
        return revoked;
    }

    // ------------------------------------------------------------------ внутреннее

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
        dropSession(family);
    }

    private void dropFamilyIfEmpty(String family) {
        Long size = redis.opsForSet().size(FAM + family);
        if (size == null || size == 0) {
            redis.delete(FAM + family);
            dropSession(family);
        }
    }

    private void writeSession(String family, UUID userId, String userEmail,
                              Instant createdAt, Instant lastUsedAt, String ip, String userAgent) {
        try {
            SessionInfo info = new SessionInfo(family, userId, userEmail, createdAt, lastUsedAt,
                    ip, truncate(userAgent));
            redis.opsForValue().set(SES + family, mapper.writeValueAsString(info), ttl);
            redis.opsForSet().add(USR + userId, family);
            redis.expire(USR + userId, ttl);
            redis.opsForSet().add(SES_INDEX, family);
        } catch (Exception e) {
            // Метаданные сессии вспомогательные: их сбой не должен ломать вход.
            log.warn("Не удалось сохранить метаданные сессии {}: {}", family, e.toString());
        }
    }

    private void touchSession(RefreshRecord record, String ip, String userAgent) {
        String key = SES + record.family();
        String json = redis.opsForValue().get(key);
        Instant createdAt = Instant.now();
        String userEmail = null;
        if (json != null) {
            SessionInfo previous = readSession(json);
            if (previous != null) {
                createdAt = previous.createdAt() == null ? createdAt : previous.createdAt();
                userEmail = previous.userEmail();
            }
        }
        // Для семей, созданных до появления метаданных, email неизвестен —
        // заполняем его при ближайшем обмене токена из справочника пользователей.
        writeSession(record.family(), record.userId(), userEmail, createdAt, Instant.now(), ip, userAgent);
    }

    private void dropSession(String family) {
        String json = redis.opsForValue().get(SES + family);
        redis.delete(SES + family);
        redis.opsForSet().remove(SES_INDEX, family);
        if (json == null) {
            return;
        }
        SessionInfo info = readSession(json);
        if (info != null && info.userId() != null) {
            redis.opsForSet().remove(USR + info.userId(), family);
        }
    }

    private RefreshRecord read(String json) {
        try {
            return mapper.readValue(json, RefreshRecord.class);
        } catch (Exception e) {
            throw new IllegalStateException("Повреждённая запись refresh-токена", e);
        }
    }

    private SessionInfo readSession(String json) {
        try {
            return mapper.readValue(json, SessionInfo.class);
        } catch (Exception e) {
            log.warn("Повреждённые метаданные сессии, запись пропущена: {}", e.toString());
            return null;
        }
    }

    private static List<SessionInfo> sorted(List<SessionInfo> sessions) {
        // свежие сверху; записи без метки времени уходят в конец
        sessions.sort(Comparator.comparing(SessionInfo::lastUsedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return List.copyOf(sessions);
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_USER_AGENT ? value : value.substring(0, MAX_USER_AGENT);
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
