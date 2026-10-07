package ru.seoniks.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.seoniks.auth.config.AppProperties;
import ru.seoniks.auth.repo.AuditLogRepository;

import java.time.Duration;
import java.time.Instant;

@Component
public class AuditRetentionTask {

    private static final Logger log = LoggerFactory.getLogger(AuditRetentionTask.class);

    private final AuditLogRepository repository;
    private final AppProperties props;

    public AuditRetentionTask(AuditLogRepository repository, AppProperties props) {
        this.repository = repository;
        this.props = props;
    }

    // В 03:30, через полчаса после дампа базы в 03:00.
    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void purge() {
        int days = props.audit() == null ? 0 : props.audit().retentionDays();
        if (days <= 0) {
            return;
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(days));
        int removed = repository.deleteOlderThan(cutoff);
        if (removed > 0) {
            log.info("Журнал аудита: удалено {} записей старше {} дней", removed, days);
        }
    }
}
