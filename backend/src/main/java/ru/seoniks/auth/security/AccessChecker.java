package ru.seoniks.auth.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Проверка доступа к страницам. Вызывается из контроллеров
 * через @PreAuthorize("@access.hasPage('TEST_PAGE_1')").
 * ADMIN имеет доступ ко всем страницам всегда.
 */
@Component("access")
public class AccessChecker {

    public boolean hasPage(String code) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        if (auth.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_ADMIN"))) {
            return true;
        }
        return auth.getAuthorities().contains(new SimpleGrantedAuthority("PAGE_" + code));
    }
}