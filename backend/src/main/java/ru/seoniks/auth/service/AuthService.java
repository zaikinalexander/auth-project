package ru.seoniks.auth.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.seoniks.auth.dto.LoginRequest;
import ru.seoniks.auth.dto.UserDto;
import ru.seoniks.auth.error.ApiException;
import ru.seoniks.auth.repo.UserRepository;
import ru.seoniks.auth.security.JwtService;
import ru.seoniks.auth.security.LoginRateLimiter;
import ru.seoniks.auth.security.RefreshTokenStore;

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

    public AuthService(UserRepository users,
                       PasswordEncoder encoder,
                       JwtService jwtService,
                       RefreshTokenStore refreshStore,
                       LoginRateLimiter rateLimiter) {
        this.users = users;
        this.encoder = encoder;
        this.jwtService = jwtService;
        this.refreshStore = refreshStore;
        this.rateLimiter = rateLimiter;
    }

    @Transactional(readOnly = true)
    public LoginResult login(LoginRequest request, String clientIp) {
        String email = request.email().toLowerCase().trim();
        String limitKey = clientIp + "|" + email;

        if (rateLimiter.isBlocked(limitKey)) {
            throw ApiException.tooManyRequests(
                    "Слишком много попыток входа. Повторите позже.");
        }

        var user = users.findByEmail(email).orElse(null);
        boolean valid = user != null
                && user.isEnabled()
                && encoder.matches(request.password(), user.getPasswordHash());

        if (!valid) {
            rateLimiter.registerFailure(limitKey);
            throw ApiException.unauthorized("Неверный email или пароль");
        }

        rateLimiter.reset(limitKey);

        String accessToken = jwtService.issueAccessToken(user.getId(), user.getRole().name());
        String refreshToken = refreshStore.create(user.getId());
        return new LoginResult(accessToken, refreshToken, UserDto.from(user));
    }

    @Transactional(readOnly = true)
    public Optional<RefreshResult> refresh(String refreshToken) {
        RefreshTokenStore.RotateResult rotated = refreshStore.rotate(refreshToken);
        if (rotated.status() != RefreshTokenStore.RotateStatus.OK) {
            return Optional.empty();
        }

        return users.findWithPagesById(rotated.userId())
                .filter(u -> u.isEnabled())
                .map(user -> {
                    String accessToken =
                            jwtService.issueAccessToken(user.getId(), user.getRole().name());
                    return new RefreshResult(accessToken, rotated.newToken());
                })
                .or(() -> {
                    // пользователь удалён/отключён между ротацией и проверкой
                    refreshStore.revoke(rotated.newToken());
                    return Optional.empty();
                });
    }

    public void logout(String refreshToken) {
        refreshStore.revoke(refreshToken);
    }

    @Transactional(readOnly = true)
    public UserDto me(UUID userId) {
        return users.findWithPagesById(userId)
                .map(UserDto::from)
                .orElseThrow(() -> ApiException.unauthorized("Сессия недействительна"));
    }
}