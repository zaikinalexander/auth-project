package ru.seoniks.auth.web;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.seoniks.auth.dto.AssignPagesRequest;
import ru.seoniks.auth.dto.CreateUserRequest;
import ru.seoniks.auth.dto.UpdateUserRequest;
import ru.seoniks.auth.dto.UserDto;
import ru.seoniks.auth.service.AdminService;
import ru.seoniks.auth.service.SessionService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final AdminService adminService;
    private final SessionService sessionService;

    public AdminUserController(AdminService adminService, SessionService sessionService) {
        this.adminService = adminService;
        this.sessionService = sessionService;
    }

    @GetMapping
    public List<UserDto> list() {
        return adminService.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserDto create(@Valid @RequestBody CreateUserRequest request) {
        return adminService.create(request);
    }

    @PatchMapping("/{id}")
    public UserDto update(@PathVariable UUID id,
                          @Valid @RequestBody UpdateUserRequest request,
                          @AuthenticationPrincipal UUID currentUserId) {
        return adminService.update(id, request, currentUserId);
    }

    @PutMapping("/{id}/pages")
    public UserDto assignPages(@PathVariable UUID id,
                               @Valid @RequestBody AssignPagesRequest request,
                               @AuthenticationPrincipal UUID currentUserId) {
        return adminService.assignPages(id, request.pages(), currentUserId);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id,
                       @AuthenticationPrincipal UUID currentUserId) {
        adminService.delete(id, currentUserId);
    }

    // Обрыв всех сессий пользователя. Access-токены доживают свои 15 минут:
    // они stateless и не проверяются по хранилищу, это свойство схемы из ТЗ.
    @DeleteMapping("/{id}/sessions")
    public Map<String, Object> revokeSessions(@PathVariable UUID id) {
        return Map.of("revoked", sessionService.revokeAllForUser(id));
    }
}
