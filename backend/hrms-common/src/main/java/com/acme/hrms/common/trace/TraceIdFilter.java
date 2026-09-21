package com.acme.hrms.common.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Establishes the trace id first so security-filter errors carry it too. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

  public static final String RESPONSE_HEADER = "X-Trace-Id";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String traceId = TraceContext.fromTraceparent(request.getHeader("traceparent"));
    if (traceId == null) {
      traceId = TraceContext.newTraceId();
    }
    MDC.put(TraceContext.MDC_KEY, traceId);
    response.setHeader(RESPONSE_HEADER, traceId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(TraceContext.MDC_KEY);
    }
  }
}
