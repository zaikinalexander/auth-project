package ru.seoniks.auth.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import ru.seoniks.auth.domain.AuditEvent;
import ru.seoniks.auth.domain.Role;
import ru.seoniks.auth.domain.User;
import ru.seoniks.auth.dto.SessionDto;
import ru.seoniks.auth.error.ApiException;
import ru.seoniks.auth.repo.UserRepository;
import ru.seoniks.auth.security.RefreshTokenStore;
import ru.seoniks.auth.security.RefreshTokenStore.SessionInfo;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

    private static final UUID USER_A = UUID.randomUUID();
    private static final UUID USER_B = UUID.randomUUID();

    @Mock
    RefreshTokenStore store;

    @Mock
    UserRepository users;

    @Mock
    AuditService audit;

    @InjectMocks
    SessionService service;

    @Test
    void listAllReturnsSessionsWithMetadata() {
        when(store.listSessions()).thenReturn(List.of(
                info("fam-1", USER_A, "a@seoniks.ru", "203.0.113.5"),
                info("fam-2", USER_B, "b@seoniks.ru", "203.0.113.9")));

        List<SessionDto> sessions = service.listAll(null, null);

        assertThat(sessions).hasSize(2);
        assertThat(sessions).extracting(SessionDto::familyId).containsExactly("fam-1", "fam-2");
        assertThat(sessions).extracting(SessionDto::userEmail).containsExactly("a@seoniks.ru", "b@seoniks.ru");
        assertThat(sessions).allSatisfy(s -> {
            assertThat(s.ip()).isNotBlank();
            assertThat(s.userAgent()).isEqualTo("curl/8.5.0");
            assertThat(s.createdAt()).isNotNull();
            assertThat(s.lastUsedAt()).isNotNull();
        });
    }

    @Test
    void listAllFiltersByEmailSubstringIgnoringCase() {
        when(store.listSessions()).thenReturn(List.of(
                info("fam-1", USER_A, "owner@seoniks.ru", "203.0.113.5"),
                info("fam-2", USER_B, "user1@seoniks.ru", "203.0.113.9")));

        assertThat(service.listAll("OWNER", null)).extracting(SessionDto::familyId)
                .containsExactly("fam-1");
        assertThat(service.listAll("  ", null)).hasSize(2);
        assertThat(service.listAll("nobody@example.com", null)).isEmpty();
    }

    @Test
    void missingEmailIsResolvedFromDatabaseOncePerBatch() {
        when(store.listSessions()).thenReturn(List.of(
                info("fam-1", USER_A, null, "203.0.113.5"),
                info("fam-2", USER_B, null, "203.0.113.9")));
        when(users.findAllById(any())).thenReturn(List.of(
                user(USER_A, "a@seoniks.ru"), user(USER_B, "b@seoniks.ru")));

        List<SessionDto> sessions = service.listAll(null, null);

        assertThat(sessions).extracting(SessionDto::userEmail)
                .containsExactly("a@seoniks.ru", "b@seoniks.ru");
        verify(users).findAllById(any());
    }

    @Test
    void currentFlagIsSetOnlyForThePresentedFamily() {
        when(store.listSessionsForUser(USER_A)).thenReturn(List.of(
                info("fam-current", USER_A, "a@seoniks.ru", "203.0.113.5"),
                info("fam-other", USER_A, "a@seoniks.ru", "198.51.100.7")));

        List<SessionDto> sessions = service.listForUser(USER_A, "fam-current");

        assertThat(sessions).filteredOn(SessionDto::current)
                .extracting(SessionDto::familyId).containsExactly("fam-current");
        assertThat(sessions).filteredOn(s -> !s.current())
                .extracting(SessionDto::familyId).containsExactly("fam-other");
    }

    @Test
    void listForUserReturnsOnlyOwnSessions() {
        when(store.listSessionsForUser(USER_A))
                .thenReturn(List.of(info("fam-1", USER_A, "a@seoniks.ru", "203.0.113.5")));

        List<SessionDto> sessions = service.listForUser(USER_A, null);

        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).userId()).isEqualTo(USER_A);
        verify(store, never()).listSessions();
    }

    @Test
    void revokeFamilyWritesAuditWithFamilyAndOwner() {
        when(store.findSession("fam-1")).thenReturn(info("fam-1", USER_A, "a@seoniks.ru", "203.0.113.5"));
        when(store.revokeFamilyById("fam-1")).thenReturn(true);

        assertThat(service.revokeFamily("fam-1")).isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(AuditEvent.ADMIN_SESSION_REVOKE), eq(true), eq("a@seoniks.ru"),
                details.capture());
        assertThat(details.getValue()).containsEntry("familyId", "fam-1")
                .containsEntry("targetEmail", "a@seoniks.ru");
    }

    @Test
    void revokeUnknownFamilyReturnsFalseAndWritesNothing() {
        when(store.findSession("ghost")).thenReturn(null);
        when(store.revokeFamilyById("ghost")).thenReturn(false);

        assertThat(service.revokeFamily("ghost")).isFalse();

        verifyNoInteractions(audit);
    }

    @Test
    void revokeAllForUnknownUserGives404() {
        when(users.findById(USER_A)).thenReturn(Optional.empty());

        ApiException e = assertThrows(ApiException.class, () -> service.revokeAllForUser(USER_A));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(store, audit);
    }

    @Test
    void revokeAllForUserReportsCount() {
        when(users.findById(USER_A)).thenReturn(Optional.of(user(USER_A, "a@seoniks.ru")));
        when(store.revokeAllForUser(USER_A)).thenReturn(3);

        assertThat(service.revokeAllForUser(USER_A)).isEqualTo(3);

        verify(audit).record(eq(AuditEvent.ADMIN_SESSION_REVOKE), eq(true), eq("a@seoniks.ru"),
                eq(Map.of("targetEmail", "a@seoniks.ru", "revoked", 3)));
    }

    @Test
    void revokeOthersKeepsCurrentFamilyAndWritesSelfEvent() {
        when(store.revokeAllForUserExcept(USER_A, "fam-current")).thenReturn(2);

        assertThat(service.revokeOthers(USER_A, "fam-current")).isEqualTo(2);

        verify(store).revokeAllForUserExcept(USER_A, "fam-current");
        verify(audit).record(eq(AuditEvent.AUTH_SESSIONS_REVOKED_SELF), eq(true), isNull(),
                eq(Map.of("revoked", 2)));
    }

    @Test
    void revokeOthersWithoutCookieRevokesEverything() {
        when(store.revokeAllForUserExcept(USER_A, null)).thenReturn(4);

        assertThat(service.revokeOthers(USER_A, null)).isEqualTo(4);

        verify(store).revokeAllForUserExcept(USER_A, null);
    }

    private static SessionInfo info(String family, UUID userId, String email, String ip) {
        Instant now = Instant.now();
        return new SessionInfo(family, userId, email, now.minusSeconds(3600), now.minusSeconds(60),
                ip, "curl/8.5.0");
    }

    private static User user(UUID id, String email) {
        User u = new User();
        u.setId(id);
        u.setEmail(email);
        u.setPasswordHash("$argon2id$stub");
        u.setRole(Role.USER);
        u.setEnabled(true);
        return u;
    }
}
