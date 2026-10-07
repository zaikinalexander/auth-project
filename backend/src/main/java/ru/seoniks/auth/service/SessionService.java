package ru.seoniks.auth.service;

import org.springframework.stereotype.Service;
import ru.seoniks.auth.domain.AuditEvent;
import ru.seoniks.auth.domain.User;
import ru.seoniks.auth.dto.SessionDto;
import ru.seoniks.auth.error.ApiException;
import ru.seoniks.auth.repo.UserRepository;
import ru.seoniks.auth.security.RefreshTokenStore;
import ru.seoniks.auth.security.RefreshTokenStore.SessionInfo;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class SessionService {

    private final RefreshTokenStore store;
    private final UserRepository users;
    private final AuditService audit;

    public SessionService(RefreshTokenStore store, UserRepository users, AuditService audit) {
        this.store = store;
        this.users = users;
        this.audit = audit;
    }

    public List<SessionDto> listAll(String emailFilter, String currentFamilyId) {
        List<SessionInfo> sessions = store.listSessions();
        Map<UUID, String> resolved = resolveEmails(sessions);
        List<SessionDto> all = sessions.stream()
                .map(info -> toDto(info, resolved, currentFamilyId))
                .toList();
        if (emailFilter == null || emailFilter.isBlank()) {
            return all;
        }
        String needle = emailFilter.trim().toLowerCase(Locale.ROOT);
        return all.stream()
                .filter(dto -> dto.userEmail() != null
                        && dto.userEmail().toLowerCase(Locale.ROOT).contains(needle))
                .toList();
    }

    public List<SessionDto> listForUser(UUID userId, String currentFamilyId) {
        List<SessionInfo> sessions = store.listSessionsForUser(userId);
        Map<UUID, String> resolved = resolveEmails(sessions);
        return sessions.stream()
                .map(info -> toDto(info, resolved, currentFamilyId))
                .toList();
    }

    public boolean revokeFamily(String familyId) {
        SessionInfo info = store.findSession(familyId);
        boolean revoked = store.revokeFamilyById(familyId);
        if (revoked) {
            String email = emailOf(info);
            Map<String, Object> details = new HashMap<>();
            details.put("familyId", familyId);
            if (email != null) {
                details.put("targetEmail", email);
            }
            audit.record(AuditEvent.ADMIN_SESSION_REVOKE, true, email, details);
        }
        return revoked;
    }

    public int revokeAllForUser(UUID userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> ApiException.notFound("Пользователь не найден"));
        int revoked = store.revokeAllForUser(userId);
        audit.record(AuditEvent.ADMIN_SESSION_REVOKE, true, user.getEmail(),
                Map.of("targetEmail", user.getEmail(), "revoked", revoked));
        return revoked;
    }

    /** Свои сессии кроме текущей; текущая определяется по refresh-cookie вызывающего. */
    public int revokeOthers(UUID userId, String currentFamilyId) {
        int revoked = store.revokeAllForUserExcept(userId, currentFamilyId);
        audit.record(AuditEvent.AUTH_SESSIONS_REVOKED_SELF, true, null,
                Map.of("revoked", revoked));
        return revoked;
    }

    private String emailOf(SessionInfo info) {
        if (info == null) {
            return null;
        }
        if (info.userEmail() != null) {
            return info.userEmail();
        }
        return info.userId() == null ? null
                : users.findById(info.userId()).map(User::getEmail).orElse(null);
    }

    // Email хранится в метаданных сессии, но для семей, созданных до появления
    // метаданных, его там нет — добираем одним пакетным запросом.
    private Map<UUID, String> resolveEmails(List<SessionInfo> sessions) {
        Set<UUID> missing = new HashSet<>();
        for (SessionInfo info : sessions) {
            if (info.userEmail() == null && info.userId() != null) {
                missing.add(info.userId());
            }
        }
        if (missing.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> emails = new HashMap<>();
        users.findAllById(missing).forEach(user -> emails.put(user.getId(), user.getEmail()));
        return emails;
    }

    private SessionDto toDto(SessionInfo info, Map<UUID, String> resolved, String currentFamilyId) {
        String email = info.userEmail();
        if (email == null && info.userId() != null) {
            email = resolved.get(info.userId());
        }
        boolean current = currentFamilyId != null && currentFamilyId.equals(info.familyId());
        return new SessionDto(info.familyId(), info.userId(), email, info.createdAt(),
                info.lastUsedAt(), info.ip(), info.userAgent(), current);
    }
}
