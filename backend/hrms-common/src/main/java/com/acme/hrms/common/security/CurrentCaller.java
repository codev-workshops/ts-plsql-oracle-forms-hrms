package com.acme.hrms.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;

/** Reads the {@link CallerIdentity} placed in the security context by the JWT filter. */
public final class CurrentCaller {

  private CurrentCaller() {}

  public static CallerIdentity require() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof CallerIdentity id) {
      return id;
    }
    throw new NotAuthenticatedException();
  }

  public static final class NotAuthenticatedException extends AuthenticationException {
    NotAuthenticatedException() {
      super("No authenticated caller");
    }
  }
}
