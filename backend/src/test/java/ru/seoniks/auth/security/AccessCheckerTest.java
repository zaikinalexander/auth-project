package ru.seoniks.auth.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.seoniks.auth.domain.AuditEvent;
import ru.seoniks.auth.service.AuditService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AccessCheckerTest {

    @Mock
    AuditService audit;

    @InjectMocks
    AccessChecker checker;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void adminPassesForAnyCodeIncludingUnknownOnes() {
        authenticate("ROLE_ADMIN");

        assertThat(checker.hasPage("TEST_PAGE_1")).isTrue();
        assertThat(checker.hasPage("REPORT_2026_Q3")).isTrue();
        assertThat(checker.hasPage("NOTHING_LIKE_THIS_EXISTS")).isTrue();
        verifyNoInteractions(audit);
    }

    @Test
    void userPassesForGrantedDynamicCode() {
        // код не зашит в AccessChecker: достаточно права PAGE_<код>, пришедшего из JWT
        authenticate("ROLE_USER", "PAGE_DASHBOARD_2026");

        assertThat(checker.hasPage("DASHBOARD_2026")).isTrue();
        verifyNoInteractions(audit);
    }

    @Test
    void userPassesForEveryGrantedCodeAndNoOther() {
        authenticate("ROLE_USER", "PAGE_AAA", "PAGE_BBB");

        assertThat(checker.hasPage("AAA")).isTrue();
        assertThat(checker.hasPage("BBB")).isTrue();
        assertThat(checker.hasPage("CCC")).isFalse();
    }

    @Test
    void denialWritesPageDeniedAuditWithCode() {
        authenticate("ROLE_USER", "PAGE_AAA");

        assertThat(checker.hasPage("BBB")).isFalse();

        verify(audit).record(eq(AuditEvent.PAGE_DENIED), eq(false), eq("BBB"),
                eq(Map.of("code", "BBB")));
    }

    @Test
    void allowedAccessWritesNothing() {
        authenticate("ROLE_USER", "PAGE_AAA");

        checker.hasPage("AAA");

        verifyNoInteractions(audit);
    }

    @Test
    void unauthenticatedCallerIsDeniedWithoutAudit() {
        assertThat(checker.hasPage("TEST_PAGE_1")).isFalse();

        verifyNoInteractions(audit);
    }

    @Test
    void prefixCollisionDoesNotGrantAccess() {
        // право PAGE_ABC не должно открывать ни ABCD, ни AB
        authenticate("ROLE_USER", "PAGE_ABC");

        assertThat(checker.hasPage("ABC")).isTrue();
        assertThat(checker.hasPage("ABCD")).isFalse();
        assertThat(checker.hasPage("AB")).isFalse();
    }

    private void authenticate(String... authorities) {
        List<SimpleGrantedAuthority> granted = new ArrayList<>();
        for (String authority : authorities) {
            granted.add(new SimpleGrantedAuthority(authority));
        }
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(UUID.randomUUID(), null, granted));
    }
}
