package ru.seoniks.auth.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import ru.seoniks.auth.domain.Page;
import ru.seoniks.auth.domain.Role;
import ru.seoniks.auth.domain.User;
import ru.seoniks.auth.dto.CreateUserRequest;
import ru.seoniks.auth.dto.UpdateUserRequest;
import ru.seoniks.auth.dto.UserDto;
import ru.seoniks.auth.error.ApiException;
import ru.seoniks.auth.repo.PageRepository;
import ru.seoniks.auth.repo.UserRepository;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminServiceTest {

    private static final String PASSWORD = "long-enough-password";

    @Mock
    UserRepository users;

    @Mock
    PageRepository pages;

    @Mock
    PasswordEncoder encoder;

    @InjectMocks
    AdminService service;

    @Test
    void deleteOwnAccountIsRejected() {
        UUID id = UUID.randomUUID();

        ApiException e = assertThrows(ApiException.class, () -> service.delete(id, id));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(users, never()).deleteById(any(UUID.class));
    }

    @Test
    void deleteOtherUserSucceeds() {
        UUID target = UUID.randomUUID();
        UUID current = UUID.randomUUID();
        when(users.existsById(target)).thenReturn(true);

        service.delete(target, current);

        verify(users).deleteById(target);
    }

    @Test
    void deleteUnknownUserGives404() {
        UUID target = UUID.randomUUID();
        when(users.existsById(target)).thenReturn(false);

        ApiException e = assertThrows(ApiException.class,
                () -> service.delete(target, UUID.randomUUID()));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void changeOwnRoleIsRejected() {
        UUID id = UUID.randomUUID();
        when(users.findWithPagesById(id)).thenReturn(Optional.of(user(id, "self@seoniks.ru", Role.ADMIN)));

        ApiException e = assertThrows(ApiException.class,
                () -> service.update(id, new UpdateUserRequest(Role.USER, null), id));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void disableOwnAccountIsRejected() {
        UUID id = UUID.randomUUID();
        when(users.findWithPagesById(id)).thenReturn(Optional.of(user(id, "self@seoniks.ru", Role.ADMIN)));

        ApiException e = assertThrows(ApiException.class,
                () -> service.update(id, new UpdateUserRequest(null, false), id));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void updateOfUnknownUserGives404EvenForSelf() {
        UUID id = UUID.randomUUID();
        when(users.findWithPagesById(id)).thenReturn(Optional.empty());

        ApiException e = assertThrows(ApiException.class,
                () -> service.update(id, new UpdateUserRequest(Role.USER, null), id));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void unknownPageCodeIsRejected() {
        when(users.findByEmail("new@seoniks.ru")).thenReturn(Optional.empty());
        when(pages.findByCodeIn(anyCollection())).thenReturn(List.of());

        ApiException e = assertThrows(ApiException.class, () -> service.create(
                new CreateUserRequest("New@Seoniks.ru", PASSWORD, Role.USER, List.of("NO_SUCH_PAGE"))));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(users, never()).save(any(User.class));
    }

    @Test
    void duplicateEmailGives409() {
        when(users.findByEmail("dup@seoniks.ru"))
                .thenReturn(Optional.of(user(UUID.randomUUID(), "dup@seoniks.ru", Role.USER)));

        ApiException e = assertThrows(ApiException.class, () -> service.create(
                new CreateUserRequest("dup@seoniks.ru", PASSWORD, Role.USER, List.of())));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        verify(users, never()).save(any(User.class));
    }

    @Test
    void createStoresArgonHashAndNormalizesEmail() {
        when(users.findByEmail("new@seoniks.ru")).thenReturn(Optional.empty());
        when(pages.findByCodeIn(anyCollection())).thenReturn(List.of(page(1L, "TEST_PAGE_1")));
        when(encoder.encode(PASSWORD)).thenReturn("$argon2id$stub");
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserDto created = service.create(
                new CreateUserRequest("  New@Seoniks.ru  ", PASSWORD, Role.USER, List.of("TEST_PAGE_1")));

        assertThat(created.email()).isEqualTo("new@seoniks.ru");
        assertThat(created.role()).isEqualTo(Role.USER);
        assertThat(created.pages()).containsExactly("TEST_PAGE_1");
        assertThat(created.enabled()).isTrue();
        verify(users).save(any(User.class));
    }

    @Test
    void assignPagesReplacesWholeSet() {
        UUID id = UUID.randomUUID();
        User target = user(id, "u@seoniks.ru", Role.USER);
        target.getPages().add(page(1L, "TEST_PAGE_1"));
        when(users.findWithPagesById(id)).thenReturn(Optional.of(target));
        when(pages.findByCodeIn(anyCollection())).thenReturn(List.of(page(2L, "TEST_PAGE_2")));

        UserDto updated = service.assignPages(id, List.of("TEST_PAGE_2"), UUID.randomUUID());

        assertThat(updated.pages()).containsExactly("TEST_PAGE_2");
    }

    private static User user(UUID id, String email, Role role) {
        User u = new User();
        u.setId(id);
        u.setEmail(email);
        u.setPasswordHash("$argon2id$stub");
        u.setRole(role);
        u.setEnabled(true);
        u.setPages(new HashSet<>());
        return u;
    }

    private static Page page(Long id, String code) {
        Page p = new Page();
        p.setId(id);
        p.setCode(code);
        p.setTitle(code);
        return p;
    }
}
