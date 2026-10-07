package ru.seoniks.auth.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import ru.seoniks.auth.domain.AuditEvent;
import ru.seoniks.auth.domain.Role;
import ru.seoniks.auth.domain.User;
import ru.seoniks.auth.dto.LoginRequest;
import ru.seoniks.auth.dto.UserDto;
import ru.seoniks.auth.error.ApiException;
import ru.seoniks.auth.repo.UserRepository;
import ru.seoniks.auth.security.JwtService;
import ru.seoniks.auth.security.LoginRateLimiter;
import ru.seoniks.auth.security.RefreshTokenStore;

import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String IP = "203.0.113.7";
    private static final String EMAIL = "user@seoniks.ru";
    private static final String KEY = IP + "|" + EMAIL;
    private static final String HASH = "$argon2id$stub";

    @Mock
    UserRepository users;

    @Mock
    PasswordEncoder encoder;

    @Mock
    JwtService jwtService;

    @Mock
    RefreshTokenStore refreshStore;

    @Mock
    LoginRateLimiter rateLimiter;

    @Mock
    AuditService audit;

    @InjectMocks
    AuthService service;

    @Test
    void wrongPasswordGives401AndCountsFailure() {
        User user = user(UUID.randomUUID(), Role.USER, true);
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(encoder.matches("wrong-password", HASH)).thenReturn(false);

        ApiException e = assertThrows(ApiException.class,
                () -> service.login(new LoginRequest(EMAIL, "wrong-password"), IP));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(rateLimiter).registerFailure(KEY);
        verify(rateLimiter, never()).reset(anyString());
        verifyNoInteractions(refreshStore);
        verify(audit).record(eq(AuditEvent.AUTH_LOGIN_FAIL), eq(false), eq(EMAIL),
                anyMap(), eq(EMAIL), isNull());
    }

    @Test
    void unknownEmailGives401AndCountsFailure() {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.empty());

        ApiException e = assertThrows(ApiException.class,
                () -> service.login(new LoginRequest(EMAIL, "any-password"), IP));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(rateLimiter).registerFailure(KEY);
        verifyNoInteractions(refreshStore);
    }

    @Test
    void disabledUserGives401WithoutCheckingPassword() {
        User user = user(UUID.randomUUID(), Role.USER, false);
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        ApiException e = assertThrows(ApiException.class,
                () -> service.login(new LoginRequest(EMAIL, "any-password"), IP));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(encoder, never()).matches(anyString(), anyString());
        verify(rateLimiter).registerFailure(KEY);
    }

    @Test
    void blockedKeyGives429BeforeTouchingDatabase() {
        when(rateLimiter.isBlocked(KEY)).thenReturn(true);

        ApiException e = assertThrows(ApiException.class,
                () -> service.login(new LoginRequest(EMAIL, "any-password"), IP));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verifyNoInteractions(users, encoder, jwtService, refreshStore);
    }

    @Test
    void successfulLoginIssuesBothTokensAndClearsFailures() {
        UUID id = UUID.randomUUID();
        User user = user(id, Role.ADMIN, true);
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(encoder.matches("correct-password", HASH)).thenReturn(true);
        when(jwtService.issueAccessToken(id, "ADMIN")).thenReturn("access-token");
        when(refreshStore.create(id)).thenReturn("refresh-token");

        AuthService.LoginResult result = service.login(new LoginRequest(EMAIL, "correct-password"), IP);

        assertThat(result.accessToken()).isEqualTo("access-token");
        assertThat(result.refreshToken()).isEqualTo("refresh-token");
        assertThat(result.user().email()).isEqualTo(EMAIL);
        assertThat(result.user().role()).isEqualTo(Role.ADMIN);
        verify(rateLimiter).reset(KEY);
        verify(audit).record(eq(AuditEvent.AUTH_LOGIN_SUCCESS), eq(true), eq(EMAIL),
                anyMap(), eq(EMAIL), eq("ADMIN"));
    }

    @Test
    void emailIsNormalizedBeforeLookupAndRateLimitKey() {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.empty());

        assertThrows(ApiException.class,
                () -> service.login(new LoginRequest("  User@Seoniks.ru  ", "any-password"), IP));

        verify(users).findByEmail(EMAIL);
        verify(rateLimiter).registerFailure(KEY);
    }

    @Test
    void refreshRotatesTokenForActiveUser() {
        UUID id = UUID.randomUUID();
        when(refreshStore.rotate("old-token")).thenReturn(rotated(
                RefreshTokenStore.RotateStatus.OK, id, "new-token"));
        when(users.findWithPagesById(id)).thenReturn(Optional.of(user(id, Role.USER, true)));
        when(jwtService.issueAccessToken(id, "USER")).thenReturn("access-token");

        Optional<AuthService.RefreshResult> result = service.refresh("old-token");

        assertThat(result).isPresent();
        assertThat(result.get().accessToken()).isEqualTo("access-token");
        assertThat(result.get().newRefreshToken()).isEqualTo("new-token");
    }

    @Test
    void refreshForDisabledUserRevokesRotatedToken() {
        UUID id = UUID.randomUUID();
        when(refreshStore.rotate("old-token")).thenReturn(rotated(
                RefreshTokenStore.RotateStatus.OK, id, "new-token"));
        when(users.findWithPagesById(id)).thenReturn(Optional.of(user(id, Role.USER, false)));

        assertThat(service.refresh("old-token")).isEmpty();

        verify(refreshStore).revoke("new-token");
    }

    @Test
    void refreshWithUnknownTokenReturnsEmpty() {
        when(refreshStore.rotate("bad-token")).thenReturn(rotated(
                RefreshTokenStore.RotateStatus.INVALID, null, null));

        assertThat(service.refresh("bad-token")).isEmpty();

        verifyNoInteractions(users, jwtService);
        verify(audit).record(eq(AuditEvent.AUTH_REFRESH_INVALID), eq(false), isNull(), isNull());
    }

    @Test
    void refreshAfterReuseDetectionReturnsEmpty() {
        when(refreshStore.rotate("stolen-token")).thenReturn(rotated(
                RefreshTokenStore.RotateStatus.REUSE, null, null));

        assertThat(service.refresh("stolen-token")).isEmpty();

        verifyNoInteractions(users, jwtService);
        verify(audit).record(eq(AuditEvent.AUTH_REFRESH_REUSE), eq(false), isNull(), isNull());
    }

    @Test
    void meReturnsUserWithPages() {
        UUID id = UUID.randomUUID();
        when(users.findWithPagesById(id)).thenReturn(Optional.of(user(id, Role.ADMIN, true)));

        UserDto me = service.me(id);

        assertThat(me.id()).isEqualTo(id);
        assertThat(me.email()).isEqualTo(EMAIL);
    }

    @Test
    void meForMissingUserGives401() {
        UUID id = UUID.randomUUID();
        when(users.findWithPagesById(id)).thenReturn(Optional.empty());

        ApiException e = assertThrows(ApiException.class, () -> service.me(id));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private static RefreshTokenStore.RotateResult rotated(RefreshTokenStore.RotateStatus status,
                                                         UUID userId,
                                                         String newToken) {
        return new RefreshTokenStore.RotateResult(status, userId, newToken);
    }

    private static User user(UUID id, Role role, boolean enabled) {
        User u = new User();
        u.setId(id);
        u.setEmail(EMAIL);
        u.setPasswordHash(HASH);
        u.setRole(role);
        u.setEnabled(enabled);
        u.setPages(new HashSet<>());
        return u;
    }
}
