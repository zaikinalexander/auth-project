package ru.seoniks.auth.dto;

import ru.seoniks.auth.domain.AuditLog;

import java.time.Instant;
import java.util.Map;

public record AuditLogDto(
        Long id,
        Instant ts,
        String actorEmail,
        String actorRole,
        String event,
        String ip,
        String userAgent,
        String target,
        Map<String, Object> details,
        Boolean success
) {
    public static AuditLogDto from(AuditLog entry) {
        return new AuditLogDto(
                entry.getId(),
                entry.getTs(),
                entry.getActorEmail(),
                entry.getActorRole(),
                entry.getEvent() == null ? null : entry.getEvent().name(),
                entry.getIp(),
                entry.getUserAgent(),
                entry.getTarget(),
                entry.getDetails(),
                entry.getSuccess());
    }
}
