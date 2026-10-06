package ru.seoniks.auth.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import ru.seoniks.auth.config.AppProperties;
import ru.seoniks.auth.domain.Role;
import ru.seoniks.auth.domain.User;
import ru.seoniks.auth.repo.PageRepository;
import ru.seoniks.auth.repo.UserRepository;

import java.util.HashSet;

/**
 * При первом запуске создаёт администратора, если пользователей нет.
 * Пароль берётся из переменной окружения ADMIN_INITIAL_PASSWORD.
 */
@Configuration
public class DataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    @Bean
    ApplicationRunner initAdmin(UserRepository users,
                                PageRepository pages,
                                PasswordEncoder encoder,
                                AppProperties props) {
        return args -> {
            if (users.count() > 0) {
                return;
            }
            String password = props.admin().initialPassword();
            if (password == null || password.isBlank()) {
                throw new IllegalStateException(
                        "База пуста, но ADMIN_INITIAL_PASSWORD не задан. Создайте администратора.");
            }

            User admin = new User();
            admin.setEmail(props.admin().initialEmail().toLowerCase());
            admin.setPasswordHash(encoder.encode(password));
            admin.setRole(Role.ADMIN);
            admin.setEnabled(true);
            admin.setPages(new HashSet<>(pages.findAll()));
            users.save(admin);

            log.info("Создан первичный администратор: {}", admin.getEmail());
        };
    }
}