/**
 * `ApiError.code` vocabulary from contracts/p0-foundation/error-codes.md.
 * Legacy codes are the Oracle -20xxx numbers carried verbatim as strings.
 */
export const LegacyErrorCode = {
  INVALID_CREDENTIALS: '-20301',
  PASSWORD_TOO_SHORT: '-20310',
  PASSWORD_NO_UPPERCASE: '-20311',
  PASSWORD_NO_DIGIT: '-20312',
  EMPLOYEE_NOT_FOUND: '-20001',
  CYCLE_STATUS_INVALID: '-20401',
  REVIEW_STATUS_INVALID: '-20402',
  RATING_OUT_OF_RANGE: '-20403',
} as const;

export const FrameworkErrorCode = {
  VALIDATION_FAILED: 'VALIDATION_FAILED',
  PASSWORD_REUSED: 'PASSWORD_REUSED',
  TOKEN_INVALID: 'TOKEN_INVALID',
  FORBIDDEN: 'FORBIDDEN',
  SSO_MODULE_NOT_LEGACY: 'SSO_MODULE_NOT_LEGACY',
  RATE_LIMITED: 'RATE_LIMITED',
  SSO_LEGACY_UNAVAILABLE: 'SSO_LEGACY_UNAVAILABLE',
  INTERNAL_ERROR: 'INTERNAL_ERROR',
  CYCLE_NOT_FOUND: 'CYCLE_NOT_FOUND',
  REVIEW_NOT_FOUND: 'REVIEW_NOT_FOUND',
  GOAL_NOT_FOUND: 'GOAL_NOT_FOUND',
} as const;

export type LegacyErrorCode = (typeof LegacyErrorCode)[keyof typeof LegacyErrorCode];
export type FrameworkErrorCode = (typeof FrameworkErrorCode)[keyof typeof FrameworkErrorCode];
export type KnownErrorCode = LegacyErrorCode | FrameworkErrorCode;

export const KNOWN_ERROR_CODES: ReadonlySet<string> = new Set<string>([
  ...Object.values(LegacyErrorCode),
  ...Object.values(FrameworkErrorCode),
]);

/**
 * Field the UI should attach each field-level code to when the server omits `field`
 * (error-codes.md §1, `field` column).
 */
export const DEFAULT_FIELD_FOR_CODE: Readonly<Record<string, string>> = {
  [LegacyErrorCode.PASSWORD_TOO_SHORT]: 'newPassword',
  [LegacyErrorCode.PASSWORD_NO_UPPERCASE]: 'newPassword',
  [LegacyErrorCode.PASSWORD_NO_DIGIT]: 'newPassword',
  [FrameworkErrorCode.PASSWORD_REUSED]: 'newPassword',
  [LegacyErrorCode.RATING_OUT_OF_RANGE]: 'overallRating',
};
