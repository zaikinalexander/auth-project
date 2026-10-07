package ru.seoniks.auth.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.seoniks.auth.domain.AuditEvent;
import ru.seoniks.auth.domain.Page;
import ru.seoniks.auth.domain.User;
import ru.seoniks.auth.dto.CreateUserRequest;
import ru.seoniks.auth.dto.UpdateUserRequest;
import ru.seoniks.auth.dto.UserDto;
import ru.seoniks.auth.error.ApiException;
import ru.seoniks.auth.repo.PageRepository;
import ru.seoniks.auth.repo.UserRepository;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class AdminService {

    private final UserRepository users;
    private final PageRepository pages;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public AdminService(UserRepository users,
                        PageRepository pages,
                        PasswordEncoder encoder,
                        AuditService audit) {
        this.users = users;
        this.pages = pages;
        this.encoder = encoder;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<UserDto> list() {
        return users.findAllWithPages().stream().map(UserDto::from).toList();
    }

    @Transactional
    public UserDto create(CreateUserRequest request) {
        String email = request.email().toLowerCase().trim();
        if (users.findByEmail(email).isPresent()) {
            throw ApiException.conflict("Пользователь с таким email уже существует");
        }

        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(encoder.encode(request.password()));
        user.setRole(request.role());
        user.setEnabled(true);
        user.setPages(loadPages(request.pages()));

        UserDto created = UserDto.from(users.save(user));
        // В details уходят только email, роль и коды страниц — пароль и его хеш никогда.
        audit.record(AuditEvent.ADMIN_USER_CREATE, true, created.email(),
                Map.of("email", created.email(),
                        "role", created.role().name(),
                        "pages", created.pages()));
        return created;
    }

    @Transactional
    public UserDto update(UUID id, UpdateUserRequest request, UUID currentUserId) {
        User user = requireUser(id);

        boolean touchesRoleOrStatus = request.role() != null || request.enabled() != null;
        if (touchesRoleOrStatus && id.equals(currentUserId)) {
            throw ApiException.badRequest(
                    "Нельзя изменить собственную роль или статус");
        }

        String roleBefore = user.getRole() == null ? null : user.getRole().name();
        boolean enabledBefore = user.isEnabled();

        if (request.role() != null) {
            user.setRole(request.role());
        }
        if (request.enabled() != null) {
            user.setEnabled(request.enabled());
        }

        Map<String, String> changes = new LinkedHashMap<>();
        String roleAfter = user.getRole() == null ? null : user.getRole().name();
        if (request.role() != null && !Objects.equals(roleBefore, roleAfter)) {
            changes.put("role", roleBefore + " -> " + roleAfter);
        }
        if (request.enabled() != null && enabledBefore != user.isEnabled()) {
            changes.put("enabled", enabledBefore + " -> " + user.isEnabled());
        }

        UserDto updated = UserDto.from(user);
        audit.record(AuditEvent.ADMIN_USER_UPDATE, true, updated.email(),
                Map.of("targetEmail", updated.email(), "changes", changes));
        return updated;
    }

    @Transactional
    public UserDto assignPages(UUID id, List<String> pageCodes, UUID currentUserId) {
        User user = requireUser(id);
        List<String> before = sortedCodes(user.getPages());

        user.setPages(loadPages(pageCodes));

        List<String> after = sortedCodes(user.getPages());
        UserDto updated = UserDto.from(user);
        audit.record(AuditEvent.ADMIN_USER_PAGES, true, updated.email(),
                Map.of("targetEmail", updated.email(),
                        "pagesBefore", before,
                        "pagesAfter", after));
        return updated;
    }

    @Transactional
    public void delete(UUID id, UUID currentUserId) {
        if (id.equals(currentUserId)) {
            throw ApiException.badRequest("Нельзя удалить собственную учётную запись");
        }
        User user = requireUser(id);
        users.delete(user);
        audit.record(AuditEvent.ADMIN_USER_DELETE, true, user.getEmail(),
                Map.of("targetEmail", user.getEmail()));
    }

    private User requireUser(UUID id) {
        return users.findWithPagesById(id)
                .orElseThrow(() -> ApiException.notFound("Пользователь не найден"));
    }

    private Set<Page> loadPages(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return new HashSet<>();
        }
        List<String> distinct = codes.stream().distinct().toList();
        List<Page> found = pages.findByCodeIn(distinct);
        if (found.size() != distinct.size()) {
            throw ApiException.badRequest("Один или несколько кодов страниц неизвестны");
        }
        return new HashSet<>(found);
    }

    private static List<String> sortedCodes(Set<Page> source) {
        return source.stream().map(Page::getCode).sorted().toList();
    }
}
