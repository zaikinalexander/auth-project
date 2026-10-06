package ru.seoniks.auth.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.seoniks.auth.domain.Page;
import ru.seoniks.auth.domain.User;
import ru.seoniks.auth.dto.CreateUserRequest;
import ru.seoniks.auth.dto.UpdateUserRequest;
import ru.seoniks.auth.dto.UserDto;
import ru.seoniks.auth.error.ApiException;
import ru.seoniks.auth.repo.PageRepository;
import ru.seoniks.auth.repo.UserRepository;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class AdminService {

    private final UserRepository users;
    private final PageRepository pages;
    private final PasswordEncoder encoder;

    public AdminService(UserRepository users, PageRepository pages, PasswordEncoder encoder) {
        this.users = users;
        this.pages = pages;
        this.encoder = encoder;
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

        return UserDto.from(users.save(user));
    }

    @Transactional
    public UserDto update(UUID id, UpdateUserRequest request, UUID currentUserId) {
        User user = requireUser(id);

        boolean touchesRoleOrStatus = request.role() != null || request.enabled() != null;
        if (touchesRoleOrStatus && id.equals(currentUserId)) {
            throw ApiException.badRequest(
                    "Нельзя изменить собственную роль или статус");
        }

        if (request.role() != null) {
            user.setRole(request.role());
        }
        if (request.enabled() != null) {
            user.setEnabled(request.enabled());
        }
        return UserDto.from(user);
    }

    @Transactional
    public UserDto assignPages(UUID id, List<String> pageCodes, UUID currentUserId) {
        User user = requireUser(id);
        user.setPages(loadPages(pageCodes));
        return UserDto.from(user);
    }

    @Transactional
    public void delete(UUID id, UUID currentUserId) {
        if (id.equals(currentUserId)) {
            throw ApiException.badRequest("Нельзя удалить собственную учётную запись");
        }
        if (!users.existsById(id)) {
            throw ApiException.notFound("Пользователь не найден");
        }
        users.deleteById(id);
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
}