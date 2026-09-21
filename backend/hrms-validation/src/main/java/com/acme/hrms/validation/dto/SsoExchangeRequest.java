package com.acme.hrms.validation.dto;

import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class SsoExchangeRequest {

  @NotNull
  @FieldMeta(requiredMessage = "Module is required")
  private ProxyModule module;

  @NotBlank
  @Size(max = 50)
  @FieldMeta(trim = true, requiredMessage = "Client IP is required")
  private String clientIp;

  @Size(max = 500)
  @Pattern(regexp = "^/[^\\s]*$")
  @FieldMeta(trim = true, patternMessage = "Return path must be a relative path")
  private String returnPath;

  public ProxyModule getModule() {
    return module;
  }

  public void setModule(ProxyModule module) {
    this.module = module;
  }

  public String getClientIp() {
    return clientIp;
  }

  public void setClientIp(String clientIp) {
    this.clientIp = clientIp == null ? null : clientIp.trim();
  }

  public String getReturnPath() {
    return returnPath;
  }

  public void setReturnPath(String returnPath) {
    this.returnPath = returnPath == null ? null : returnPath.trim();
  }
}
