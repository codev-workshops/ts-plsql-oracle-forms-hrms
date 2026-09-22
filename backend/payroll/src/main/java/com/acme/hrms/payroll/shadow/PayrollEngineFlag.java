package com.acme.hrms.payroll.shadow;

/**
 * Current value of the {@code payroll.engine} proxy flag ({@code LEGACY|JAVA}); provided by the
 * application module that owns the flag vocabulary. Reported on shadow diffs, never used to gate
 * the shadow route.
 */
@FunctionalInterface
public interface PayrollEngineFlag {
  String current();
}
