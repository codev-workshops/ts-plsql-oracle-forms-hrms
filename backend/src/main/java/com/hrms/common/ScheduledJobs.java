package com.hrms.common;

import com.hrms.leave.LeaveAccrualService;
import com.hrms.leave.LeaveCarryoverService;
import com.hrms.notification.NotificationDispatcher;
import com.hrms.payroll.PayrollBatch;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Replaces the DBMS_SCHEDULER jobs: monthly leave accrual (PKG-L04), year-end carryover and daily carryover
 * expiry (PKG-L05), the notification queue (PKG-N01), the payroll batch (PKG-P07) and the audit purge
 * (PKG-A02, separately gated by {@code hrms.jobs.audit-purge-enabled}). Off unless
 * {@code hrms.jobs.enabled=true}, so tests and extra instances never run them by accident.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "hrms.jobs.enabled", havingValue = "true")
class ScheduledJobs {

    static final String ACCRUAL_ACTOR = "system:leave-accrual";
    static final String CARRYOVER_ACTOR = "system:leave-carryover";

    private final LeaveAccrualService accrual;
    private final LeaveCarryoverService carryover;
    private final NotificationDispatcher dispatcher;
    private final PayrollBatch payroll;
    private final AuditRetentionService auditRetention;
    private final boolean auditPurgeEnabled;
    private final Clock clock;

    ScheduledJobs(LeaveAccrualService accrual, LeaveCarryoverService carryover, NotificationDispatcher dispatcher,
            PayrollBatch payroll, AuditRetentionService auditRetention,
            @Value("${hrms.jobs.audit-purge-enabled:false}") boolean auditPurgeEnabled, Clock clock) {
        this.accrual = accrual;
        this.carryover = carryover;
        this.dispatcher = dispatcher;
        this.payroll = payroll;
        this.auditRetention = auditRetention;
        this.auditPurgeEnabled = auditPurgeEnabled;
        this.clock = clock;
    }

    @Scheduled(cron = "${hrms.jobs.leave-accrual-cron:0 0 1 1 * *}", zone = "UTC")
    void monthlyAccrual() {
        accrual.run(LocalDate.now(clock), ACCRUAL_ACTOR);
    }

    /** 1 January, before the month's accrual: carries the year that just ended into the new one. */
    @Scheduled(cron = "${hrms.jobs.leave-carryover-cron:0 30 0 1 1 *}", zone = "UTC")
    void yearEndCarryover() {
        carryover.carryOver(LocalDate.now(clock).getYear() - 1, CARRYOVER_ACTOR);
    }

    @Scheduled(cron = "${hrms.jobs.leave-carryover-expiry-cron:0 45 0 * * *}", zone = "UTC")
    void carryoverExpiry() {
        carryover.expire(LocalDate.now(clock), CARRYOVER_ACTOR);
    }

    @Scheduled(fixedDelayString = "${hrms.jobs.notification-delay:PT5M}")
    void dispatchNotifications() {
        dispatcher.requeueFailed(3);
        dispatcher.dispatchPending(50);
    }

    @Scheduled(cron = "${hrms.jobs.payroll-batch-cron:0 0 2 * * *}", zone = "UTC")
    void payrollBatch() {
        payroll.calculatePendingRuns();
    }

    @Scheduled(cron = "${hrms.jobs.audit-purge-cron:0 0 3 * * *}", zone = "UTC")
    void auditPurge() {
        if (auditPurgeEnabled) {
            auditRetention.purge();
        }
    }
}
