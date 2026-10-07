package ru.seoniks.auth.dto;

import ru.seoniks.auth.domain.Page;

import java.time.Instant;

public record PageDto(
        String code,
        String title,
        String content,
        Instant createdAt
) {
    public static PageDto from(Page page) {
        return new PageDto(page.getCode(), page.getTitle(), page.getContent(), page.getCreatedAt());
    }
}
