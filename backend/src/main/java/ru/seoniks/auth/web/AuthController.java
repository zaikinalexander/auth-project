package ru.seoniks.auth.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.seoniks.auth.dto.ChangePasswordRequest;
import ru.seoniks.auth.dto.LoginRequest;
import ru.seoniks.auth.dto.LoginResponse;
import ru.seoniks.auth.dto.UserDto;
import ru.seoniks.auth.error.ApiException;
import ru.seoniks.auth.security.CookieFactory;
import ru.seoniks.auth.service.AuthService;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final CookieFactory cookies;

    public AuthController(AuthService authService, CookieFactory cookies) {
        this.authService = authService;
        this.cookies = cookies;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                               HttpServletRequest httpRequest,
                                               HttpServletResponse httpResponse) {
        AuthService.LoginResult result =
                authService.login(request, httpRequest.getRemoteAddr(), userAgent(httpRequest));
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, cookies.create(result.refreshToken()).toString());
        return ResponseEntity.ok(new LoginResponse(result.accessToken(), result.user()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<Map<String, String>> refresh(HttpServletRequest httpRequest,
                                                       HttpServletResponse httpResponse) {
        String token = cookies.readRefresh(httpRequest);
        if (token == null) {
            throw ApiException.unauthorized("Требуется вход");
        }

        return authService.refresh(token, httpRequest.getRemoteAddr(), userAgent(httpRequest))
                .map(result -> {
                    httpResponse.addHeader(HttpHeaders.SET_COOKIE,
                            cookies.create(result.newRefreshToken()).toString());
                    return ResponseEntity.ok(Map.of("accessToken", result.accessToken()));
                })
                .orElseGet(() -> {
                    httpResponse.addHeader(HttpHeaders.SET_COOKIE, cookies.expire().toString());
                    throw ApiException.unauthorized("Сессия недействительна, войдите снова");
                });
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest httpRequest,
                                       HttpServletResponse httpResponse) {
        authService.logout(cookies.readRefresh(httpRequest));
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, cookies.expire().toString());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public UserDto me(@AuthenticationPrincipal UUID userId) {
        return authService.me(userId);
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest request,
                               @AuthenticationPrincipal UUID userId,
                               HttpServletResponse httpResponse) {
        authService.changePassword(userId, request.currentPassword(), request.newPassword());
        // Все refresh-токены отозваны, поэтому cookie гасим сразу: иначе браузер
        // носил бы заведомо мёртвый токен и получал 401 на каждом обновлении.
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, cookies.expire().toString());
    }

    private static String userAgent(HttpServletRequest request) {
        return request.getHeader(HttpHeaders.USER_AGENT);
    }
}
