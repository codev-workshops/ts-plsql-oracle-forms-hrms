package com.acme.hrms.auth.service;

import com.acme.hrms.auth.config.AuthProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Sliding window of failed logins per (lower-cased) username: after {@code maxFailures} in {@code
 * window} the next attempt is refused with 429 before the password is even checked. In-process
 * state - one auth-service instance in Phase 0 (CUTOVER_PLAN.md §4); a shared store is a Phase 1
 * concern. Replaces the *absence* of lockout in PKG_SECURITY (SEC-02).
 */
@Component
public class LoginRateLimiter {

  private final int maxFailures;
  private final Duration window;
  private final Clock clock;
  private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

  @Autowired
  public LoginRateLimiter(AuthProperties props, Clock clock) {
    this(props.rateLimit().maxFailures(), props.rateLimit().window(), clock);
  }

  public LoginRateLimiter(int maxFailures, Duration window, Clock clock) {
    this.maxFailures = maxFailures;
    this.window = window;
    this.clock = clock;
  }

  /** Seconds until the next attempt is allowed, or empty when the username is not blocked. */
  public OptionalLong retryAfterSeconds(String username) {
    Deque<Instant> q = failures.get(key(username));
    if (q == null) {
      return OptionalLong.empty();
    }
    synchronized (q) {
      prune(q);
      if (q.size() < maxFailures) {
        return OptionalLong.empty();
      }
      long secs = Duration.between(clock.instant(), q.peekFirst().plus(window)).getSeconds();
      return OptionalLong.of(Math.max(1, secs));
    }
  }

  public void recordFailure(String username) {
    Deque<Instant> q = failures.computeIfAbsent(key(username), k -> new ArrayDeque<>());
    synchronized (q) {
      prune(q);
      q.addLast(clock.instant());
    }
  }

  public void reset(String username) {
    failures.remove(key(username));
  }

  private void prune(Deque<Instant> q) {
    Instant cutoff = clock.instant().minus(window);
    while (!q.isEmpty() && !q.peekFirst().isAfter(cutoff)) {
      q.pollFirst();
    }
  }

  private static String key(String username) {
    return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
  }
}
