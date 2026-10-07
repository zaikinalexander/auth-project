package ru.seoniks.auth.dto;

import java.time.Instant;
import java.util.UUID;

public record SessionDto(
        String familyId,
        UUID userId,
        String userEmail,
        Instant createdAt,
        Instant lastUsedAt,
        String ip,
        String userAgent,
        boolean current
) {}
