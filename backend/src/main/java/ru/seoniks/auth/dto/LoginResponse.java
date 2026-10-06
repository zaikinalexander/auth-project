package ru.seoniks.auth.dto;

/**
 * ВАЖНО: refresh-токен НИКОГДА не попадает в JSON-тело.
 * Он передаётся только в HttpOnly Cookie.
 */
public record LoginResponse(
        String accessToken,
        UserDto user
) {}