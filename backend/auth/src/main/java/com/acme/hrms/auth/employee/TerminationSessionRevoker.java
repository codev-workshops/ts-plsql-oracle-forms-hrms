package com.acme.hrms.auth.employee;

import com.acme.hrms.auth.repo.SessionRepository;
import com.acme.hrms.auth.repo.UserAccountRepository;
import com.acme.hrms.auth.service.SessionRevoker;
import com.acme.hrms.employee.EmployeeSessionRevoker;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * BUG-07: {@code AuthService.revokeSessions(empId)} - closes every ACTIVE session of the terminated
 * employee, revokes their refresh tokens and access-token jtis and disables the login. Joins the
 * termination transaction so the kill and the status change commit together.
 */
@Component
public class TerminationSessionRevoker implements EmployeeSessionRevoker {

  private final SessionRepository sessions;
  private final SessionRevoker revoker;
  private final UserAccountRepository accounts;
  private final Clock clock;

  public TerminationSessionRevoker(
      SessionRepository sessions,
      SessionRevoker revoker,
      UserAccountRepository accounts,
      Clock clock) {
    this.sessions = sessions;
    this.revoker = revoker;
    this.accounts = accounts;
    this.clock = clock;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void revokeSessions(long empId, String actor) {
    Instant now = clock.instant();
    for (long sessionId : sessions.activeSessions(empId)) {
      revoker.close(sessionId, now);
    }
    accounts.disableByEmployee(empId, now, actor);
  }
}
