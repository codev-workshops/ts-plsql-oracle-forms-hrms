package com.hrms.common;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PKG_AUDIT.purge_old_records (PKG-A02): deletes {@code audit_log} rows older than the retention
 * ({@code hrms.audit.retention}, legacy 365 days) and records the purge itself as an audit row. The only
 * physical delete the application role may perform (D-03 exception, A-P8-02). Scheduled only when
 * {@code hrms.jobs.audit-purge-enabled=true} (compliance sign-off, OQ-P8-1).
 */
@Service
public class AuditRetentionService {

    public static final String ACTOR = "system:audit-purge";

    private final JdbcClient jdbc;
    private final AuditWriter audit;
    private final Clock clock;
    private final Duration retention;

    public AuditRetentionService(JdbcClient jdbc, AuditWriter audit, Clock clock,
            @Value("${hrms.audit.retention:P365D}") Duration retention) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        this.retention = retention;
    }

    public record PurgeResult(OffsetDateTime purgedBefore, int deleted) {
    }

    @Transactional
    public PurgeResult purge() {
        OffsetDateTime cutoff = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MICROS).minus(retention);
        int deleted = jdbc.sql("DELETE FROM hrms.audit_log WHERE changed_date < :cutoff")
                .param("cutoff", cutoff).update();
        audit.write("AUDIT_LOG", 0, "DELETE", null, Map.of("purgedBefore", cutoff.toString(), "rows", deleted),
                ACTOR, null, null);
        return new PurgeResult(cutoff, deleted);
    }
}
