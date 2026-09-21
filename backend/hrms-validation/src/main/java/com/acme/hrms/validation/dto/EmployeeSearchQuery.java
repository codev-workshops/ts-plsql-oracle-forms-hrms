package com.acme.hrms.validation.dto;

import com.acme.hrms.validation.meta.AllowedValues;
import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Query parameters of GET /api/employees (the P0 picker projection only). */
public class EmployeeSearchQuery {

  public static final String PROJECTION = "id,name,jobTitle";

  @NotNull
  @AllowedValues({"ACTIVE"})
  private String status;

  @NotNull
  @AllowedValues({PROJECTION})
  private String fields;

  @Size(max = 100)
  @FieldMeta(trim = true, maxLengthMessage = "Search text is too long")
  private String q;

  /** Not a validated field (boolean flag, openapi.yaml). Identity comes from the JWT. */
  private Boolean excludeSelf;

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

  public String getFields() {
    return fields;
  }

  public void setFields(String fields) {
    this.fields = fields;
  }

  public String getQ() {
    return q;
  }

  public void setQ(String q) {
    this.q = q == null ? null : q.trim();
  }

  public Boolean getExcludeSelf() {
    return excludeSelf;
  }

  public void setExcludeSelf(Boolean excludeSelf) {
    this.excludeSelf = excludeSelf;
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
