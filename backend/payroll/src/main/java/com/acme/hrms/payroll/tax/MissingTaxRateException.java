package com.acme.hrms.payroll.tax;

import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;

/** MISSING_TAX_RATE: recorded as the employee's ERROR row; 422 when re-raised by the payslip. */
public class MissingTaxRateException extends HrmsException {

  public MissingTaxRateException(int taxYear, String key) {
    super(
        ErrorCode.MISSING_TAX_RATE,
        String.format(ErrorCode.MISSING_TAX_RATE.defaultMessage(), taxYear, key),
        null);
  }
}
