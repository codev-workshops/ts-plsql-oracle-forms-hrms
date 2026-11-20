package com.hrms.payroll;

import com.hrms.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * The payroll batch (PKG-P07) that the PKG_PAYROLL header attributed to DBMS_SCHEDULER: calculates every PENDING
 * run, each in its own transaction, as {@code system:payroll-batch}. A run that is locked or no longer PENDING
 * is skipped; the others still run. Scheduled by {@code ScheduledJobs}.
 */
@Component
public class PayrollBatch {

    static final String JOB = "payroll-batch";
    private static final Logger LOG = LoggerFactory.getLogger(PayrollBatch.class);

    private final PayrollRunService runs;

    public PayrollBatch(PayrollRunService runs) {
        this.runs = runs;
    }

    /** Returns the number of runs calculated. */
    public int calculatePendingRuns() {
        int done = 0;
        for (long runId : runs.pendingRunIds()) {
            try {
                runs.calculate(PayrollCaller.system(JOB), runId);
                done++;
            } catch (ApiException | PessimisticLockingFailureException e) {
                LOG.warn("Payroll batch skipped run {}: {}", runId, e.getMessage());
            }
        }
        return done;
    }
}
