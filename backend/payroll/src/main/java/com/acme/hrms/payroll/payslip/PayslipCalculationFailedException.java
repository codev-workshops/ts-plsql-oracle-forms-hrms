package com.acme.hrms.payroll.payslip;

import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;

/**
 * Re-raises the recorded {@code errorCode} of an employee's ERROR row as a 422, whatever status the
 * code carries when raised live (e.g. {@code -20104} is 404 on the salary routes).
 */
public class PayslipCalculationFailedException extends HrmsException {

  public PayslipCalculationFailedException(@Nullable String storedCode, @Nullable String message) {
    super(
        resolve(storedCode),
        message == null || message.isBlank() ? resolve(storedCode).defaultMessage() : message,
        null);
  }

  private static ErrorCode resolve(@Nullable String storedCode) {
    if (storedCode == null) {
      return ErrorCode.INTERNAL_ERROR;
    }
    try {
      return ErrorCode.fromValue(storedCode);
    } catch (IllegalArgumentException e) {
      return ErrorCode.INTERNAL_ERROR;
    }
  }

  @Override
  public HttpStatus status() {
    return HttpStatus.UNPROCESSABLE_ENTITY;
  }
}
