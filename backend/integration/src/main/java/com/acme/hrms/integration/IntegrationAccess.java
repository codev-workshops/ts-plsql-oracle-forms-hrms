package com.acme.hrms.integration;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

/**
 * Download scope of {@code /api/integration/files/{fileId}/content}: GL and imports need {@code
 * PAYROLL:VIEW}, benefits {@code EMPLOYEE:VIEW}. Unknown files fall through to the 404 handler.
 */
@Component("integrationAccess")
public class IntegrationAccess {
  private final IntegrationFileRepository files;

  public IntegrationAccess(IntegrationFileRepository files) {
    this.files = files;
  }

  public boolean canDownload(UUID fileId, Authentication authentication) {
    return files
        .get(fileId)
        .map(f -> has(authentication, requiredAuthority(f.feed())))
        .orElse(true);
  }

  static String requiredAuthority(String feed) {
    return "BENEFITS_FEED".equals(feed) ? "EMPLOYEE:VIEW" : "PAYROLL:VIEW";
  }

  private static boolean has(Authentication a, String authority) {
    for (GrantedAuthority g : a.getAuthorities()) {
      if (authority.equals(g.getAuthority())) {
        return true;
      }
    }
    return false;
  }
}
