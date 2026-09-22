package com.acme.hrms.validation.dto.payroll;

import com.acme.hrms.validation.meta.AllowedValues;
import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/** Query of GET /api/payroll/periods (PAY_PERIODS.CHK_PERIOD_STATUS filter, Spring `sort`). */
public class PayPeriodListQuery {

  @AllowedValues({"OPEN", "PROCESSING", "CLOSED", "REVERSED"})
  private String status;

  @Pattern(regexp = PayrollConstants.PERIOD_SORT_PATTERN)
  @FieldMeta(
      patternMessage = "sort must be <periodStartDate|periodEndDate|payDate|periodName>,<asc|desc>")
  private String sort;

  @Min(0)
  private Integer page;

  @Min(1)
  @Max(100)
  private Integer size;

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getSort() {
    return sort;
  }

  public void setSort(String sort) {
    this.sort = sort;
  }

  public Integer getPage() {
    return page;
  }

  public void setPage(Integer page) {
    this.page = page;
  }

  public Integer getSize() {
    return size;
  }

  public void setSize(Integer size) {
    this.size = size;
  }
}
