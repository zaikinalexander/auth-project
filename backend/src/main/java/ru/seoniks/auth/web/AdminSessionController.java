package ru.seoniks.auth.web;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.seoniks.auth.dto.SessionDto;
import ru.seoniks.auth.error.ApiException;
import ru.seoniks.auth.service.SessionService;

import java.util.List;

@RestController
@RequestMapping("/api/admin/sessions")
@PreAuthorize("hasRole('ADMIN')")
public class AdminSessionController {

    private final SessionService sessionService;

    public AdminSessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @GetMapping
    public List<SessionDto> list(@RequestParam(name = "user", required = false) String user) {
        return sessionService.listAll(user, null);
    }

    @DeleteMapping("/{familyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable String familyId) {
        if (!sessionService.revokeFamily(familyId)) {
            throw ApiException.notFound("Сессия не найдена или уже завершена");
        }
    }
}
