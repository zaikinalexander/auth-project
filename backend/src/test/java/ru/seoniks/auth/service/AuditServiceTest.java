package ru.seoniks.auth.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import ru.seoniks.auth.domain.AuditEvent;
import ru.seoniks.auth.domain.AuditLog;
import ru.seoniks.auth.domain.Role;
import ru.seoniks.auth.domain.User;
import ru.seoniks.auth.dto.AuditPageDto;
import ru.seoniks.auth.repo.AuditLogRepository;
import ru.seoniks.auth.repo.UserRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    private static final String PASSWORD = "SuperSecret123!";
    private static final String HASH = "$argon2id$v=19$m=16,t=2,p=1$c29tZXNhbHQ$ZmFrZWhhc2h2YWx1ZQ";
    private static final String ACCESS = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhYmMifQ.c2lnbmF0dXJl";
    private static final String REFRESH = "ZmFrZS1yZWZyZXNoLXRva2VuLXZhbHVlLTEyMzQ1Njc4OTA";
    private static final String COOKIE = "refresh_token=" + REFRESH + "; Path=/api/auth; Secure; HttpOnly";

    @Mock
    AuditLogRepository repository;

    @Mock
    UserRepository users;

    @Mock
    PlatformTransactionManager transactionManager;

    private AuditService service;

    @BeforeEach
    void setUp() {
        service = new AuditService(repository, users, transactionManager);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    // ------------------------------------------------------------------ T1: утечки

    @Test
    void forbiddenFieldsAreDroppedEvenWhenPresentInTheDraft() {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("email", "user@seoniks.ru");
        draft.put("reason", "bad_credentials");
        draft.put("password", PASSWORD);
        draft.put("passwordHash", HASH);
        draft.put("accessToken", ACCESS);
        draft.put("refreshToken", REFRESH);
        draft.put("token", REFRESH);
        draft.put("cookie", COOKIE);
        draft.put("Authorization", "Bearer " + ACCESS);
        draft.put("secret", "another-secret");
        draft.put("credential", "cred");
        draft.put("jwt", ACCESS);
        draft.put("sessionId", "sess-1");

        service.record(AuditEvent.AUTH_LOGIN_FAIL, false, "user@seoniks.ru", draft,
                "user@seoniks.ru", null);

        AuditLog saved = capture();
        assertThat(saved.getDetails())
                .containsOnlyKeys("email", "reason")
                .containsEntry("email", "user@seoniks.ru")
                .containsEntry("reason", "bad_credentials");

        String serialized = String.valueOf(saved.getDetails());
        assertThat(serialized).doesNotContain(PASSWORD);
        assertThat(serialized).doesNotContain(HASH);
        assertThat(serialized).doesNotContain(ACCESS);
        assertThat(serialized).doesNotContain(REFRESH);
    }

    @Test
    void sanitizeRejectsAnyKeyOutsideTheAllowList() {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("anythingElse", "value");
        draft.put("email", "ok@seoniks.ru");

        assertThat(service.sanitize(draft)).containsOnlyKeys("email");
    }

    @Test
    void sanitizeReturnsNullWhenNothingSurvives() {
        assertThat(service.sanitize(null)).isNull();
        assertThat(service.sanitize(Map.of())).isNull();
        assertThat(service.sanitize(Map.of("password", PASSWORD))).isNull();
    }

    @Test
    void nullDetailValuesAreSkipped() {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("email", null);
        draft.put("reason", "disabled");

        assertThat(service.sanitize(draft)).containsOnlyKeys("reason");
    }

    @Test
    void persistedEntryCarriesNoSecretsInAnyColumn() {
        withRequest("203.0.113.9", "curl/8.5.0");
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("email", "user@seoniks.ru");
        draft.put("password", PASSWORD);

        service.record(AuditEvent.AUTH_LOGIN_SUCCESS, true, "user@seoniks.ru", draft,
                "user@seoniks.ru", "USER");

        AuditLog saved = capture();
        String whole = saved.getActorEmail() + "|" + saved.getTarget() + "|"
                + saved.getIp() + "|" + saved.getUserAgent() + "|" + saved.getDetails();
        assertThat(whole).doesNotContain(PASSWORD).doesNotContain(HASH)
                .doesNotContain(ACCESS).doesNotContain(REFRESH);
    }

    // ------------------------------------------------------------------ поля записи

    @Test
    void userAgentIsTruncatedTo256() {
        withRequest("203.0.113.9", "U".repeat(600));

        service.record(AuditEvent.PAGE_VIEW, true, "TEST_PAGE_1", Map.of("code", "TEST_PAGE_1"));

        assertThat(capture().getUserAgent()).hasSize(AuditService.MAX_USER_AGENT);
    }

    @Test
    void targetIsTruncatedTo256() {
        service.record(AuditEvent.PAGE_VIEW, true, "T".repeat(600), null);

        assertThat(capture().getTarget()).hasSize(AuditService.MAX_TARGET);
    }

    @Test
    void ipIsTruncatedTo45() {
        withRequest("9".repeat(120), "curl");

        service.record(AuditEvent.PAGE_VIEW, true, "TEST_PAGE_1", null);

        assertThat(capture().getIp()).hasSize(AuditService.MAX_IP);
    }

    @Test
    void ipAndUserAgentComeFromTheCurrentRequest() {
        withRequest("203.0.113.9", "Mozilla/5.0 Test");

        service.record(AuditEvent.AUTH_LOGOUT, true, null, null);

        AuditLog saved = capture();
        assertThat(saved.getIp()).isEqualTo("203.0.113.9");
        assertThat(saved.getUserAgent()).isEqualTo("Mozilla/5.0 Test");
    }

    @Test
    void worksWithoutHttpRequestContext() {
        service.record(AuditEvent.AUTH_LOGOUT, true, null, null);

        AuditLog saved = capture();
        assertThat(saved.getIp()).isNull();
        assertThat(saved.getUserAgent()).isNull();
    }

    @Test
    void eventTimestampAndSuccessAreStored() {
        Instant before = Instant.now();

        service.record(AuditEvent.ADMIN_USER_DELETE, true, "gone@seoniks.ru", null);

        AuditLog saved = capture();
        assertThat(saved.getEvent()).isEqualTo(AuditEvent.ADMIN_USER_DELETE);
        assertThat(saved.getSuccess()).isTrue();
        assertThat(saved.getTs()).isBetween(before, Instant.now());
    }

    // ------------------------------------------------------------------ актёр

    @Test
    void actorIsResolvedFromSecurityContext() {
        UUID id = UUID.randomUUID();
        User user = user(id, "admin@seoniks.ru", Role.ADMIN);
        when(users.findById(id)).thenReturn(Optional.of(user));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(id, null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        service.record(AuditEvent.ADMIN_USER_CREATE, true, "new@seoniks.ru", null);

        AuditLog saved = capture();
        assertThat(saved.getActorEmail()).isEqualTo("admin@seoniks.ru");
        assertThat(saved.getActorRole()).isEqualTo("ADMIN");
    }

    @Test
    void fallbackActorIsUsedWhenThereIsNoAuthentication() {
        service.record(AuditEvent.AUTH_LOGIN_FAIL, false, "user@seoniks.ru", null,
                "user@seoniks.ru", "USER");

        AuditLog saved = capture();
        assertThat(saved.getActorEmail()).isEqualTo("user@seoniks.ru");
        assertThat(saved.getActorRole()).isEqualTo("USER");
    }

    @Test
    void unknownPrincipalFallsBackToExplicitActor() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(UUID.randomUUID(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        when(users.findById(any(UUID.class))).thenReturn(Optional.empty());

        service.record(AuditEvent.AUTH_REFRESH_INVALID, false, null, null,
                "ghost@seoniks.ru", null);

        assertThat(capture().getActorEmail()).isEqualTo("ghost@seoniks.ru");
    }

    // ------------------------------------------------------------------ чтение журнала

    @Test
    @SuppressWarnings("unchecked")
    void searchSortsByTimestampDescending() {
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.search(0, 25, null, null, null, null);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(any(Specification.class), captor.capture());
        Pageable pageable = captor.getValue();
        assertThat(pageable.getSort().getOrderFor("ts")).isNotNull();
        assertThat(pageable.getSort().getOrderFor("ts").isDescending()).isTrue();
        assertThat(pageable.getPageSize()).isEqualTo(25);
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchClampsPageAndSize() {
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.search(-5, 10_000, null, null, null, null);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(any(Specification.class), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isZero();
        assertThat(captor.getValue().getPageSize()).isEqualTo(AuditService.MAX_PAGE_SIZE);
    }

    @Test
    void searchReturnsPaginationMetadata() {
        AuditLog entry = new AuditLog();
        entry.setId(7L);
        entry.setTs(Instant.now());
        entry.setEvent(AuditEvent.PAGE_VIEW);
        entry.setSuccess(true);
        Page<AuditLog> page = new PageImpl<>(List.of(entry),
                org.springframework.data.domain.PageRequest.of(2, 1), 5);
        when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

        AuditPageDto dto = service.search(2, 1, null, null, null, null);

        assertThat(dto.page()).isEqualTo(2);
        assertThat(dto.size()).isEqualTo(1);
        assertThat(dto.total()).isEqualTo(5);
        assertThat(dto.totalPages()).isEqualTo(5);
        assertThat(dto.items()).hasSize(1);
        assertThat(dto.items().get(0).event()).isEqualTo("PAGE_VIEW");
    }

    // ------------------------------------------------------------------ разбор фильтров

    @Test
    void parseEventAcceptsKnownValuesOnly() {
        assertThat(AuditService.parseEvent("AUTH_LOGIN_FAIL")).isEqualTo("AUTH_LOGIN_FAIL");
        assertThat(AuditService.parseEvent("auth_login_fail")).isEqualTo("AUTH_LOGIN_FAIL");
        assertThat(AuditService.parseEvent("  page_view ")).isEqualTo("PAGE_VIEW");
        assertThat(AuditService.parseEvent("NO_SUCH_EVENT")).isNull();
        assertThat(AuditService.parseEvent("")).isNull();
        assertThat(AuditService.parseEvent(null)).isNull();
    }

    @Test
    void parseInstantAcceptsIsoAndDateOnly() {
        assertThat(AuditService.parseInstant("2026-10-06T12:30:00Z"))
                .isEqualTo(Instant.parse("2026-10-06T12:30:00Z"));
        assertThat(AuditService.parseInstant("2026-10-06"))
                .isEqualTo(LocalDate.of(2026, 10, 6).atStartOfDay(ZoneOffset.UTC).toInstant());
        assertThat(AuditService.parseInstant("не дата")).isNull();
        assertThat(AuditService.parseInstant("")).isNull();
        assertThat(AuditService.parseInstant(null)).isNull();
    }

    @Test
    void normalizeActorTrimsAndKeepsNull() {
        assertThat(AuditService.normalizeActor("  Owner@seoniks.ru ")).isEqualTo("Owner@seoniks.ru");
        assertThat(AuditService.normalizeActor("   ")).isNull();
        assertThat(AuditService.normalizeActor(null)).isNull();
    }

    @Test
    void specIsBuiltForEveryFilterCombination() {
        assertThat(AuditService.buildSpec(null, null, null, null)).isNotNull();
        assertThat(AuditService.buildSpec("PAGE_VIEW", "owner", Instant.now(), Instant.now())).isNotNull();
    }

    // ------------------------------------------------------------------ утилиты

    private AuditLog capture() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    private void withRequest(String remoteAddr, String userAgent) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        request.addHeader("User-Agent", userAgent);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static User user(UUID id, String email, Role role) {
        User u = new User();
        u.setId(id);
        u.setEmail(email);
        u.setPasswordHash(HASH);
        u.setRole(role);
        u.setEnabled(true);
        u.setPages(new HashSet<>());
        return u;
    }
}
