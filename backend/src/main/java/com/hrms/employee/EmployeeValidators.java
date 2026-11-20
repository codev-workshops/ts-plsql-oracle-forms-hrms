package com.hrms.employee;

import java.time.LocalDate;
import java.util.regex.Pattern;

/**
 * The single server-side home of the Forms/PLL field validators (PLL-001..004, FRM-034, DEF-04). NULL is valid
 * for every check, as in the PLL; "required" is decided by the caller.
 */
public final class EmployeeValidators {

    /** PKG_COMMON.is_valid_email: the server regex the form used, kept as the one rule (DEF-04). */
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Pattern NINE_DIGITS = Pattern.compile("^\\d{9}$");

    private EmployeeValidators() {
    }

    public static boolean isValidEmail(String email) {
        return email == null || EMAIL.matcher(email).matches();
    }

    /** PLL validate_phone / PKG_COMMON.is_valid_phone: 10 or 11 digits once non-digits are removed. */
    public static boolean isValidPhone(String phone) {
        if (phone == null) {
            return true;
        }
        int digits = phone.replaceAll("[^0-9]", "").length();
        return digits == 10 || digits == 11;
    }

    /**
     * PLL validate_ssn (the stricter rule, OQ-04): 9 digits once dashes are removed, and no all-zero area (000),
     * group (00) or serial (0000).
     */
    public static boolean isValidSsn(String ssn) {
        if (ssn == null) {
            return true;
        }
        String d = ssn.replace("-", "");
        return NINE_DIGITS.matcher(d).matches() && !d.startsWith("000") && !d.substring(3, 5).equals("00")
                && !d.substring(5).equals("0000");
    }

    /** PLL validate_date_not_future, against the injected clock's date. */
    public static boolean isNotFuture(LocalDate date, LocalDate today) {
        return date == null || !date.isAfter(today);
    }
}
