package ru.seoniks.auth.web;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.seoniks.auth.dto.*;
import ru.seoniks.auth.service.AdminService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final AdminService adminService;

    public AdminUserController(AdminService adminService) {
        this.adminService = adminService;
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
}