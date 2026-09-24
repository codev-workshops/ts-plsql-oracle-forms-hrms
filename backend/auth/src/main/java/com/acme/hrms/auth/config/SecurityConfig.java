package com.acme.hrms.auth.config;

import com.acme.hrms.auth.jwt.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Stateless resource-server style security. 401/403 raised inside the filter chain are routed to
 * GlobalExceptionHandler through the {@link HandlerExceptionResolver}, so ApiError has exactly one
 * builder (error-codes.md §3).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  public SecurityFilterChain filterChain(
      HttpSecurity http,
      JwtAuthenticationFilter jwtFilter,
      HandlerExceptionResolver handlerExceptionResolver)
      throws Exception {
    http.csrf(csrf -> csrf.disable())
        .cors(cors -> cors.disable())
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .formLogin(f -> f.disable())
        .httpBasic(b -> b.disable())
        .logout(l -> l.disable())
        .anonymous(a -> a.disable())
        .requestCache(rc -> rc.disable())
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/refresh")
                    .permitAll()
                    .requestMatchers("/actuator/health", "/actuator/health/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            eh ->
                eh.authenticationEntryPoint(
                        (request, response, e) ->
                            handlerExceptionResolver.resolveException(
                                request, response, null, new TokenInvalidException(e)))
                    .accessDeniedHandler(
                        (request, response, e) ->
                            handlerExceptionResolver.resolveException(request, response, null, e)))
        .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  /** Marker so the handler renders TOKEN_INVALID for missing/expired/revoked tokens. */
  public static final class TokenInvalidException extends AuthenticationException {
    public TokenInvalidException(Throwable cause) {
      super("Missing, expired or revoked access token", cause);
    }
  }
}
