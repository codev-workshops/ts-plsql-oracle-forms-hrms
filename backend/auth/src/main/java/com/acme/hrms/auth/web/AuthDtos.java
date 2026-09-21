package com.acme.hrms.auth.web;

import java.util.Set;
import org.springframework.lang.Nullable;

/** Wire shapes of /api/auth/* exactly as frozen in openapi.yaml. */
public final class AuthDtos {

  private AuthDtos() {}

  public record CurrentUser(
      String userId,
      long empId,
      String empNumber,
      String email,
      String firstName,
      String lastName,
      String displayName,
      @Nullable Long deptId,
      @Nullable String jobTitle,
      Set<String> roles,
      boolean mustChangePassword) {}

  public record TokenResponse(
      String accessToken, String tokenType, int expiresIn, CurrentUser user) {
    public static final String BEARER = "Bearer";
  }
}
