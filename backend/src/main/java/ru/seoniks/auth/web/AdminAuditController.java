package ru.seoniks.auth.web;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.seoniks.auth.domain.AuditEvent;
import ru.seoniks.auth.dto.AuditPageDto;
import ru.seoniks.auth.service.AuditService;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/audit")
// Маршрут уже закрыт правилом /api/admin/** -> hasRole("ADMIN") в SecurityConfig,
// здесь вторая проверка на уровне метода.
@PreAuthorize("hasRole('ADMIN')")
public class AdminAuditController {

    private final AuditService auditService;

    public AdminAuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    public AuditPageDto list(@RequestParam(name = "page", defaultValue = "0") int page,
                             @RequestParam(name = "size", defaultValue = "50") int size,
                             @RequestParam(name = "event", required = false) String event,
                             @RequestParam(name = "actor", required = false) String actor,
                             @RequestParam(name = "from", required = false) String from,
                             @RequestParam(name = "to", required = false) String to) {
        AuditPageDto result = auditService.search(page, size, event, actor, from, to);
        // Сам факт чтения журнала тоже событие; пишется после выдачи ответа,
        // чтобы не попадать в собственную выборку.
        auditService.record(AuditEvent.AUDIT_READ, true, null,
                Map.of("count", result.items().size()));
        return result;
    }

    @PostMapping("/clear")
    public Map<String, Object> clear() {
        int deleted = auditService.clear();
        return Map.of("deleted", deleted);
    }
}
