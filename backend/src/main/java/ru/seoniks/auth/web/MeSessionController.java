package ru.seoniks.auth.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.seoniks.auth.dto.SessionDto;
import ru.seoniks.auth.security.CookieFactory;
import ru.seoniks.auth.security.JwtService;
import ru.seoniks.auth.security.RefreshTokenStore;
import ru.seoniks.auth.service.SessionService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/me/sessions")
public class MeSessionController {

    private final SessionService sessionService;
    private final RefreshTokenStore store;
    private final CookieFactory cookies;
    private final JwtService jwtService;

    public MeSessionController(SessionService sessionService,
                              RefreshTokenStore store,
                              CookieFactory cookies,
                              JwtService jwtService) {
        this.sessionService = sessionService;
        this.store = store;
        this.cookies = cookies;
        this.jwtService = jwtService;
    }

    @GetMapping
    public List<SessionDto> mine(@AuthenticationPrincipal UUID userId, HttpServletRequest request) {
        return sessionService.listForUser(userId, currentFamily(request));
    }

    @DeleteMapping("/other")
    public Map<String, Object> revokeOthers(@AuthenticationPrincipal UUID userId,
                                           HttpServletRequest request) {
        int revoked = sessionService.revokeOthers(userId, currentFamily(request));
        return Map.of("revoked", revoked);
    }

    // Сначала пробуем refresh-cookie: она приходит на маршруты /api/auth.
    // На /api/me/sessions браузер её не отправляет из-за Path=/api/auth, поэтому
    // запасной вариант — jti access-токена, связанный с семьёй при выдаче.
    private String currentFamily(HttpServletRequest request) {
        String fromCookie = store.findFamilyByToken(cookies.readRefresh(request));
        if (fromCookie != null) {
            return fromCookie;
        }
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        try {
            return store.findFamilyByAccessTokenId(
                    jwtService.parseAccessToken(header.substring(7)).getId());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
