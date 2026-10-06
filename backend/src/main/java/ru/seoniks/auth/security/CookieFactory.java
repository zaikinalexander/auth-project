package ru.seoniks.auth.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import ru.seoniks.auth.config.AppProperties;

import java.time.Duration;

/**
 * Refresh-токен живёт только в HttpOnly + Secure + SameSite=Strict cookie
 * с узким path=/api/auth, чтобы не утекать на другие маршруты.
 */
@Component
public class CookieFactory {

    private final AppProperties props;

    public CookieFactory(AppProperties props) {
        this.props = props;
    }

    public ResponseCookie create(String token) {
        return base(token).maxAge(props.refresh().ttl()).build();
    }

    public ResponseCookie expire() {
        return base("").maxAge(Duration.ZERO).build();
    }

    public String readRefresh(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        String name = props.refresh().cookieName();
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(props.refresh().cookieName(), value)
                .httpOnly(true)
                .secure(props.refresh().cookieSecure())
                .sameSite("Strict")
                .path("/api/auth");
    }
}