package ru.seoniks.auth.dto;

import ru.seoniks.auth.domain.Role;
import ru.seoniks.auth.domain.User;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UserDto(
        UUID id,
        String email,
        Role role,
        boolean enabled,
        List<String> pages,
        Instant createdAt
) {
    public static UserDto from(User user) {
        return new UserDto(
                user.getId(),
                user.getEmail(),
                user.getRole(),
                user.isEnabled(),
                user.getPages().stream().map(p -> p.getCode()).sorted().toList(),
                user.getCreatedAt());
    }
}