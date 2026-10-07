package ru.seoniks.auth.service;

import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import ru.seoniks.auth.domain.AuditEvent;
import ru.seoniks.auth.domain.AuditLog;
import ru.seoniks.auth.domain.User;
import ru.seoniks.auth.dto.AuditLogDto;
import ru.seoniks.auth.dto.AuditPageDto;
import ru.seoniks.auth.repo.AuditLogRepository;
import ru.seoniks.auth.repo.UserRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    static final int MAX_IP = 45;
    static final int MAX_USER_AGENT = 256;
    static final int MAX_TARGET = 256;
    static final int MAX_EMAIL = 320;
    static final int MAX_PAGE_SIZE = 200;

    // Единственные ключи, которым разрешено попасть в details.
    static final Set<String> ALLOWED_DETAILS = Set.of(
            "reason", "email", "targetEmail", "role", "pages",
            "pagesBefore", "pagesAfter", "changes", "code", "count",
            "title", "familyId", "revoked");

    // Второй барьер поверх белого списка: ключ с таким фрагментом отбрасывается
    // всегда, даже если его по ошибке добавят в ALLOWED_DETAILS.
    static final List<String> FORBIDDEN_KEY_PARTS = List.of(
            "password", "passwd", "pwd", "secret", "token", "cookie",
            "authorization", "credential", "hash", "jwt", "session", "key");

    private final AuditLogRepository repository;
    private final UserRepository users;
    private final TransactionTemplate requiresNew;

    public AuditService(AuditLogRepository repository,
                        UserRepository users,
                        PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.users = users;
        this.requiresNew = new TransactionTemplate(transactionManager);
        // Вызывающие методы часто работают в readOnly-транзакции, поэтому запись
        // журнала всегда ведётся в отдельной.
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void record(AuditEvent event, boolean success, String target, Map<String, Object> details) {
        record(event, success, target, details, null, null);
    }

    public void record(AuditEvent event,
                       boolean success,
                       String target,
                       Map<String, Object> details,
                       String fallbackActorEmail,
                       String fallbackActorRole) {
        try {
            requiresNew.executeWithoutResult(status -> {
                AuditLog entry = new AuditLog();
                entry.setTs(Instant.now());
                entry.setEvent(event);
                entry.setSuccess(success);
                entry.setTarget(truncate(target, MAX_TARGET));
                entry.setDetails(sanitize(details));

                Actor actor = resolveActor(fallbackActorEmail, fallbackActorRole);
                entry.setActorEmail(truncate(actor.email(), MAX_EMAIL));
                entry.setActorRole(actor.role());

                HttpServletRequest request = currentRequest();
                if (request != null) {
                    entry.setIp(truncate(request.getRemoteAddr(), MAX_IP));
                    entry.setUserAgent(truncate(request.getHeader("User-Agent"), MAX_USER_AGENT));
                }
                repository.save(entry);
            });
        } catch (Exception e) {
            // Отказ журнала не должен ронять основную операцию.
            log.warn("Не удалось записать событие аудита {}: {}", event, e.toString());
        }
    }

    /**
     * Полная очистка журнала. След действия сохраняется: после удаления пишется
     * одна запись AUDIT_CLEAR с актёром, адресом, User-Agent и числом удалённых
     * строк, поэтому факт очистки нельзя скрыть.
     */
    public int clear() {
        // Integer.valueOf обязателен: execute выводит T из возвращаемого типа,
        // а примитив int аргументом типа быть не может.
        Integer deleted = requiresNew.execute(status -> Integer.valueOf(repository.deleteAllEntries()));
        int count = deleted == null ? 0 : deleted;
        record(AuditEvent.AUDIT_CLEAR, true, null, Map.of("count", count));
        return count;
    }

    public AuditPageDto search(int page, int size, String event, String actor, String from, String to) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "ts"));

        Page<AuditLog> result = repository.findAll(
                buildSpec(parseEvent(event), normalizeActor(actor), parseInstant(from), parseInstant(to)),
                pageable);

        List<AuditLogDto> items = result.getContent().stream().map(AuditLogDto::from).toList();
        return new AuditPageDto(items, safePage, safeSize, result.getTotalElements(), result.getTotalPages());
    }

    static Specification<AuditLog> buildSpec(String event, String actor, Instant from, Instant to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (event != null) {
                predicates.add(cb.equal(root.get("event"), AuditEvent.valueOf(event)));
            }
            if (actor != null) {
                predicates.add(cb.like(cb.lower(root.get("actorEmail")),
                        "%" + actor.toLowerCase(Locale.ROOT) + "%"));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("ts"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("ts"), to));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /** Возвращает имя события, если оно известно, иначе null — фильтр просто не применяется. */
    static String parseEvent(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String candidate = raw.trim().toUpperCase(Locale.ROOT);
        for (AuditEvent value : AuditEvent.values()) {
            if (value.name().equals(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    static String normalizeActor(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    /** Принимает ISO-8601 и дату без времени; нераспознанное значение игнорируется. */
    static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // не ISO-8601 со временем, пробуем дату без времени
        }
        try {
            return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    Map<String, Object> sanitize(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        Map<String, Object> clean = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : details.entrySet()) {
            String key = entry.getKey();
            if (key == null || entry.getValue() == null) {
                continue;
            }
            if (!ALLOWED_DETAILS.contains(key)) {
                continue;
            }
            String lower = key.toLowerCase(Locale.ROOT);
            boolean forbidden = FORBIDDEN_KEY_PARTS.stream().anyMatch(lower::contains);
            if (forbidden) {
                log.warn("В details аудита передан запрещённый ключ {}, значение отброшено", key);
                continue;
            }
            clean.put(key, entry.getValue());
        }
        return clean.isEmpty() ? null : clean;
    }

    private Actor resolveActor(String fallbackEmail, String fallbackRole) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof UUID userId) {
            User user = users.findById(userId).orElse(null);
            if (user != null) {
                return new Actor(user.getEmail(), user.getRole() == null ? null : user.getRole().name());
            }
        }
        return new Actor(fallbackEmail, fallbackRole);
    }

    private static HttpServletRequest currentRequest() {
        var attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record Actor(String email, String role) {}
}
