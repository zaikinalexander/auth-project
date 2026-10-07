package ru.seoniks.auth.dto;

import java.util.List;

public record AuditPageDto(
        List<AuditLogDto> items,
        int page,
        int size,
        long total,
        int totalPages
) {}
