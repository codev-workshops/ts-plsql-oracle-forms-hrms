package com.acme.hrms.payroll.period;

import com.acme.hrms.audit.AuditService;
import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.common.security.CallerIdentity;
import com.acme.hrms.payroll.PayrollDtos.Page;
import com.acme.hrms.payroll.PayrollDtos.PayPeriod;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PKG_PAYROLL.close_pay_period and the period list. */
@Service
public class PayPeriodService {

  private final PayPeriodRepository periods;
  private final AuditService audit;

  public PayPeriodService(PayPeriodRepository periods, AuditService audit) {
    this.periods = periods;
    this.audit = audit;
  }

  public Page<PayPeriod> list(@Nullable String status, String sort, int page, int size) {
    String[] parts = sort.split(",");
    return periods.list(status, parts[0], parts.length < 2 || "asc".equals(parts[1]), page, size);
  }

  public PayPeriod get(long periodId) {
    return periods
        .findById(periodId)
        .orElseThrow(() -> new HrmsException(ErrorCode.PERIOD_NOT_FOUND));
  }

  @Transactional
  public PayPeriod close(long periodId, CallerIdentity caller) {
    String status =
        periods
            .statusForUpdate(periodId)
            .orElseThrow(() -> new HrmsException(ErrorCode.PERIOD_NOT_FOUND));
    if ("CLOSED".equals(status)) {
      throw new HrmsException(
          ErrorCode.PERIOD_CLOSED,
          String.format(ErrorCode.PERIOD_CLOSED.defaultMessage(), periodId),
          null);
    }
    periods.close(periodId, caller.userId());
    audit.log(
        "PAY_PERIODS",
        periodId,
        AuditService.Action.PERIOD_CLOSE,
        "status=" + status,
        "status=CLOSED",
        caller.userId(),
        null,
        null);
    return get(periodId);
  }
}
