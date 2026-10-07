package ru.seoniks.auth.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.seoniks.auth.domain.AuditEvent;
import ru.seoniks.auth.dto.LoginRequest;
import ru.seoniks.auth.dto.UserDto;
import ru.seoniks.auth.error.ApiException;
import ru.seoniks.auth.repo.UserRepository;
import ru.seoniks.auth.security.JwtService;
import ru.seoniks.auth.security.LoginRateLimiter;
import ru.seoniks.auth.security.RefreshTokenStore;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {

    public record LoginResult(String accessToken, String refreshToken, UserDto user) {}

    public record RefreshResult(String accessToken, String newRefreshToken) {}

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwtService;
    private final RefreshTokenStore refreshStore;
    private final LoginRateLimiter rateLimiter;
    private final AuditService audit;

    public AuthService(UserRepository users,
                       PasswordEncoder encoder,
                       JwtService jwtService,
                       RefreshTokenStore refreshStore,
                       LoginRateLimiter rateLimiter,
                       AuditService audit) {
        this.users = users;
        this.encoder = encoder;
        this.jwtService = jwtService;
        this.refreshStore = refreshStore;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public LoginResult login(LoginRequest request, String clientIp) {
        String email = request.email().toLowerCase().trim();
        String limitKey = clientIp + "|" + email;

        if (rateLimiter.isBlocked(limitKey)) {
            audit.record(AuditEvent.RATE_LIMIT_HIT, false, email,
                    Map.of("email", email), email, null);
            audit.record(AuditEvent.AUTH_LOGIN_FAIL, false, email,
                    Map.of("email", email, "reason", "rate_limited"), email, null);
            throw ApiException.tooManyRequests(
                    "Слишком много попыток входа. Повторите позже.");
        }

        var user = users.findByEmail(email).orElse(null);
        String failure = null;
        if (user == null) {
            failure = "bad_credentials";
        } else if (!user.isEnabled()) {
            failure = "disabled";
        } else if (!encoder.matches(request.password(), user.getPasswordHash())) {
            failure = "bad_credentials";
        }

        if (failure != null) {
            rateLimiter.registerFailure(limitKey);
            // В журнал идут только email, адрес, время и причина — пароль не пишется никогда.
            audit.record(AuditEvent.AUTH_LOGIN_FAIL, false, email,
                    Map.of("email", email, "reason", failure), email, null);
            throw ApiException.unauthorized("Неверный email или пароль");
        }

        rateLimiter.reset(limitKey);

        String accessToken = jwtService.issueAccessToken(user.getId(), user.getRole().name());
        String refreshToken = refreshStore.create(user.getId());
        audit.record(AuditEvent.AUTH_LOGIN_SUCCESS, true, email,
                Map.of("email", email, "role", user.getRole().name()),
                email, user.getRole().name());
        return new LoginResult(accessToken, refreshToken, UserDto.from(user));
    }

    @Transactional(readOnly = true)
    public Optional<RefreshResult> refresh(String refreshToken) {
        RefreshTokenStore.RotateResult rotated = refreshStore.rotate(refreshToken);

        if (rotated.status() == RefreshTokenStore.RotateStatus.REUSE) {
            audit.record(AuditEvent.AUTH_REFRESH_REUSE, false, null, null);
            return Optional.empty();
        }
        if (rotated.status() != RefreshTokenStore.RotateStatus.OK) {
            audit.record(AuditEvent.AUTH_REFRESH_INVALID, false, null, null);
            return Optional.empty();
        }

        return users.findWithPagesById(rotated.userId())
                .filter(u -> u.isEnabled())
                .map(user -> {
                    String accessToken =
                            jwtService.issueAccessToken(user.getId(), user.getRole().name());
                    audit.record(AuditEvent.AUTH_REFRESH_OK, true, user.getEmail(),
                            Map.of("email", user.getEmail(), "role", user.getRole().name()),
                            user.getEmail(), user.getRole().name());
                    return new RefreshResult(accessToken, rotated.newToken());
                })
                .or(() -> {
                    // пользователь удалён/отключён между ротацией и проверкой
                    refreshStore.revoke(rotated.newToken());
                    audit.record(AuditEvent.AUTH_REFRESH_INVALID, false, null,
                            Map.of("reason", "user_inactive"));
                    return Optional.empty();
                });
    }

    public void logout(String refreshToken) {
        refreshStore.revoke(refreshToken);
        audit.record(AuditEvent.AUTH_LOGOUT, true, null, null);
    }

    @Transactional(readOnly = true)
    public UserDto me(UUID userId) {
        return users.findWithPagesById(userId)
                .map(UserDto::from)
                .orElseThrow(() -> ApiException.unauthorized("Сессия недействительна"));
    }
}
