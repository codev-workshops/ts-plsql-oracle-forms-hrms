/**
 * `ApiError.code` vocabulary from contracts/p0-foundation/error-codes.md,
 * contracts/p1-performance/error-codes.md and contracts/p2-leave/error-codes.md.
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
  LEAVE_INSUFFICIENT_BALANCE: '-20201',
  LEAVE_OVERLAP: '-20202',
  LEAVE_TYPE_INVALID: '-20203',
  LEAVE_STATUS_INVALID: '-20204',
  LEAVE_DATE_ORDER: '-20210',
  LEAVE_TOO_FAR_IN_PAST: '-20211',
  LEAVE_NO_BUSINESS_DAY: '-20212',
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
  LEAVE_REQUEST_NOT_FOUND: 'LEAVE_REQUEST_NOT_FOUND',
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
  [LegacyErrorCode.LEAVE_INSUFFICIENT_BALANCE]: 'leaveTypeId',
  [LegacyErrorCode.LEAVE_OVERLAP]: 'startDate',
  [LegacyErrorCode.LEAVE_TYPE_INVALID]: 'leaveTypeId',
  [LegacyErrorCode.LEAVE_DATE_ORDER]: 'endDate',
  [LegacyErrorCode.LEAVE_TOO_FAR_IN_PAST]: 'startDate',
  [LegacyErrorCode.LEAVE_NO_BUSINESS_DAY]: 'startDate',
};
