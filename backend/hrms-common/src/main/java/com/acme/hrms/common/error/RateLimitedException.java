package com.acme.hrms.common.error;

/** {@code 429 RATE_LIMITED}: 5 failed login attempts per username per 15 minutes. */
public class RateLimitedException extends HrmsException {

  private final long retryAfterSeconds;

  public RateLimitedException(long retryAfterSeconds) {
    super(ErrorCode.RATE_LIMITED);
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public long retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
