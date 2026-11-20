/**
 * Client-side hints that mirror the server rules (EmployeeValidators: PKG_COMMON.is_valid_email,
 * PLL validate_phone). The server re-validates; these only give earlier feedback.
 */
const EMAIL = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/;

export function emailHint(value: string): string | null {
  return value === '' || EMAIL.test(value) ? null : 'Enter a valid email address';
}

export function phoneHint(value: string): string | null {
  if (value === '') return null;
  const digits = value.replace(/[^0-9]/g, '').length;
  return digits === 10 || digits === 11 ? null : 'Phone must have 10 or 11 digits';
}
