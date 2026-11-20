package com.hrms.common;

import com.hrms.leave.LeaveAccrualService;
import com.hrms.notification.NotificationDispatcher;
import com.hrms.payroll.PayrollBatch;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Replaces the DBMS_SCHEDULER jobs: monthly leave accrual (PKG-L04), the notification queue (PKG-N01) and the
 * payroll batch (PKG-P07). Off unless {@code hrms.jobs.enabled=true}, so tests and extra instances never run them by accident.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "hrms.jobs.enabled", havingValue = "true")
class ScheduledJobs {

    static final String ACCRUAL_ACTOR = "system:leave-accrual";

    private final LeaveAccrualService accrual;
    private final NotificationDispatcher dispatcher;
    private final PayrollBatch payroll;
    private final Clock clock;

    ScheduledJobs(LeaveAccrualService accrual, NotificationDispatcher dispatcher, PayrollBatch payroll, Clock clock) {
        this.accrual = accrual;
        this.dispatcher = dispatcher;
        this.payroll = payroll;
        this.clock = clock;
    }

    @Scheduled(cron = "${hrms.jobs.leave-accrual-cron:0 0 1 1 * *}", zone = "UTC")
    void monthlyAccrual() {
        accrual.run(LocalDate.now(clock), ACCRUAL_ACTOR);
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
}
