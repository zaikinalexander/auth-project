package ru.seoniks.auth.dto;

// refresh-токен намеренно не входит в тело ответа и приходит только в HttpOnly Cookie.
public record LoginResponse(
        String accessToken,
        UserDto user
) {}