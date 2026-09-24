package com.acme.hrms.common.security;

import java.util.Set;

/**
 * The authenticated caller as resolved from the JWT ({@code sub}, {@code empId}, {@code roles},
 * {@code jti}). This is the only source of caller identity (ARCH-01) - never a request parameter.
 */
public record CallerIdentity(String userId, long empId, Set<String> authorities, String jti) {}
