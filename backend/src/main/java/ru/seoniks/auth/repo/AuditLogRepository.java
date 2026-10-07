package ru.seoniks.auth.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.seoniks.auth.domain.AuditLog;

import java.time.Instant;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long>,
                                            JpaSpecificationExecutor<AuditLog> {

    @Modifying
    @Query("delete from AuditLog a where a.ts < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
