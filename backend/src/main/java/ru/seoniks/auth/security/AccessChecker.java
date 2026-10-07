package ru.seoniks.auth.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import ru.seoniks.auth.domain.AuditEvent;
import ru.seoniks.auth.service.AuditService;

import java.util.Map;

// Бин для @PreAuthorize("@access.hasPage(...)"); ADMIN проходит на любую страницу.
@Component("access")
public class AccessChecker {

    private final AuditService audit;

    public AccessChecker(AuditService audit) {
        this.audit = audit;
    }

    public boolean hasPage(String code) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        boolean allowed = auth.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_ADMIN"))
                || auth.getAuthorities().contains(new SimpleGrantedAuthority("PAGE_" + code));
        if (!allowed) {
            // Отказ по @PreAuthorize не доходит до контроллера, поэтому событие пишется здесь.
            audit.record(AuditEvent.PAGE_DENIED, false, code, Map.of("code", code));
        }
        return allowed;
    }
}
