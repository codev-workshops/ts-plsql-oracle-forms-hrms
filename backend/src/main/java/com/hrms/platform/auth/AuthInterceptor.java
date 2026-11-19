package com.hrms.platform.auth;

import com.hrms.platform.error.ApiException;
import com.hrms.platform.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Requires "Authorization: Bearer <token>" on every /api/** route except the public ones. */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String ATTRIBUTE = CurrentUser.class.getName();

    private final SessionService sessions;

    public AuthInterceptor(SessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ") || header.length() <= 7) {
            throw new ApiException(ErrorCode.AUTH_SESSION_EXPIRED, "Authentication required");
        }
        request.setAttribute(ATTRIBUTE, sessions.resolve(header.substring(7).trim()));
        return true;
    }
}
