package com.acme.hrms.auth.proxy;

import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Fails start-up on an illegal flag combination instead of routing traffic wrongly. */
@Component
public class ProxyFlagsValidator {

  private final ProxyFlags flags;

  public ProxyFlagsValidator(ProxyFlags flags) {
    this.flags = flags;
  }

  @EventListener(ApplicationStartedEvent.class)
  public void onStarted() {
    flags.validate();
  }
}
