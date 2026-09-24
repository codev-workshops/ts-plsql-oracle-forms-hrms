package com.acme.hrms.validation.dto.auth;

/**
 * Frozen role-management vocabulary (contracts/p5-reporting-decommission/README.md, served by
 * auth-module under /api/admin). {@link #AUTHORITY_PATTERN} is V4's {@code chk_rp_authority}.
 */
public final class AuthorityRules {

  public static final String AUTHORITY_PATTERN =
      "^(PAYROLL|EMPLOYEE|LEAVE|ADMIN|REPORTS|PERFORMANCE):(VIEW|EDIT|APPROVE|CREATE|ADMIN|VIEW_ALL)$";

  public static final String AUTHORITY_MESSAGE = "Authority must be MODULE:ACTION";

  /** ROLES.ROLE_CODE. */
  public static final String ROLE_CODE_PATTERN = "^[A-Z][A-Z0-9_]*$";

  public static final String ROLE_CODE_MESSAGE =
      "Role code must start with a letter and contain only A-Z, 0-9 or '_'";

  private AuthorityRules() {}
}
