package com.acme.hrms.auth.jwt;

import com.acme.hrms.auth.repo.SessionRepository;
import com.acme.hrms.common.security.CallerIdentity;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bearer token → {@link CallerIdentity} principal with the {@code roles} claim as authorities. A
 * revoked {@code jti} (logout, password change, replayed refresh) is treated as no token at all, so
 * the request ends in 401 TOKEN_INVALID from the entry point.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  public static final String ATTR_RAW_TOKEN = JwtAuthenticationFilter.class.getName() + ".token";

  private final JwtService jwt;
  private final SessionRepository sessions;

  public JwtAuthenticationFilter(JwtService jwt, SessionRepository sessions) {
    this.jwt = jwt;
    this.sessions = sessions;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
      String raw = header.substring(7).trim();
      Optional<CallerIdentity> id = jwt.verify(raw).filter(c -> !sessions.isJtiRevoked(c.jti()));
      if (id.isPresent()) {
        JwtAuthentication auth = new JwtAuthentication(id.get());
        auth.setDetails(request.getRemoteAddr());
        SecurityContextHolder.getContext().setAuthentication(auth);
        request.setAttribute(ATTR_RAW_TOKEN, raw);
      }
    }
    chain.doFilter(request, response);
  }

  /** Authentication whose principal is the {@link CallerIdentity}; name = {@code sub}. */
  public static final class JwtAuthentication extends AbstractAuthenticationToken {
    private final CallerIdentity identity;

    public JwtAuthentication(CallerIdentity identity) {
      super(authorities(identity));
      this.identity = identity;
      setAuthenticated(true);
    }

    private static List<GrantedAuthority> authorities(CallerIdentity identity) {
      return identity.authorities().stream()
          .<GrantedAuthority>map(SimpleGrantedAuthority::new)
          .toList();
    }

    @Override
    public Object getCredentials() {
      return "";
    }

    @Override
    public Object getPrincipal() {
      return identity;
    }

    @Override
    public String getName() {
      return identity.userId();
    }
  }
}
