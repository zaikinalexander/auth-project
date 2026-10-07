package ru.seoniks.auth.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import ru.seoniks.auth.domain.User;
import ru.seoniks.auth.repo.UserRepository;
import ru.seoniks.auth.security.RefreshTokenStore;

// Разово при старте восстанавливает метаданные сессий для семей refresh-токенов,
// созданных до появления ses:/usr:. Без этого такие сессии не видны
// администратору и не попадают под «оборвать все сессии пользователя».
@Component
public class SessionMetadataBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SessionMetadataBackfill.class);

    private final RefreshTokenStore store;
    private final UserRepository users;

    public SessionMetadataBackfill(RefreshTokenStore store, UserRepository users) {
        this.store = store;
        this.users = users;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int created = store.backfillSessions(
                    id -> users.findById(id).map(User::getEmail).orElse(null));
            if (created > 0) {
                log.info("Восстановлены метаданные {} сессий, созданных до обновления", created);
            }
        } catch (RuntimeException e) {
            // Дозаполнение вспомогательное: его сбой не должен мешать старту приложения.
            log.warn("Не удалось восстановить метаданные сессий: {}", e.toString());
        }
    }
}
