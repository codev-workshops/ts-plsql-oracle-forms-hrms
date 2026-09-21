package com.acme.hrms.validation.dto;

import com.acme.hrms.validation.meta.FieldMeta;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class LoginRequest {

  @NotBlank
  @Size(min = 3, max = 100)
  @Email
  @FieldMeta(
      trim = true,
      requiredMessage = "Username is required",
      formatMessage = "Enter a valid e-mail address")
  private String username;

  @NotNull
  @Size(min = 1, max = 128)
  @FieldMeta(requiredMessage = "Password is required")
  private String password;

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username == null ? null : username.trim();
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }
}
