package com.hrms.platform.validation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.regex.Pattern;

/** Shared validators (legacy PKG_VALIDATION / PKG_COMMON). Return an error message or empty. */
public final class Validators {

    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Pattern PHONE = Pattern.compile("^[0-9+()\\-. ]{7,30}$");

    private Validators() {
    }

    public static boolean isValidEmail(String email) {
        return email != null && EMAIL.matcher(email).matches();
    }

    public static boolean isValidPhone(String phone) {
        return phone != null && PHONE.matcher(phone).matches();
    }

    public static Optional<String> dateRange(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            return Optional.of("Start and end dates are required");
        }
        if (end.isBefore(start)) {
            return Optional.of("End date must be on or after start date");
        }
        return Optional.empty();
    }

    public static Optional<String> salaryForGrade(BigDecimal salary, BigDecimal min, BigDecimal max, String grade) {
        if (salary.compareTo(min) < 0) {
            return Optional.of("Salary " + salary + " is below minimum for grade " + grade);
        }
        if (salary.compareTo(max) > 0) {
            return Optional.of("Salary " + salary + " exceeds maximum for grade " + grade);
        }
        return Optional.empty();
    }

    /** Password policy from PKG_SECURITY.change_password; returns the failing legacy code. */
    public static Optional<String> passwordPolicyViolation(String password, int minLength) {
        if (password == null || password.length() < minLength) {
            return Optional.of("-20310");
        }
        if (password.chars().noneMatch(Character::isUpperCase)) {
            return Optional.of("-20311");
        }
        if (password.chars().noneMatch(Character::isDigit)) {
            return Optional.of("-20312");
        }
        return Optional.empty();
    }
}
