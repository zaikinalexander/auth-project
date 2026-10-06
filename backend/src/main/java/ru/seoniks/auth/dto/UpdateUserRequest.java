package ru.seoniks.auth.dto;

import ru.seoniks.auth.domain.Role;

/** PATCH-семантика: null = не изменять. */
public record UpdateUserRequest(
        Role role,
        Boolean enabled
) {}