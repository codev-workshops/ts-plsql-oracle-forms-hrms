import { useCallback } from 'react';
import type { ApiError } from '../api/types';
import { isApiError } from '../api/http';
import { DEFAULT_FIELD_FOR_CODE, FrameworkErrorCode, KNOWN_ERROR_CODES } from './errorCodes';
import { useToast } from './ToastContext';

/**
 * Client-side replacement for HRMS_COMMON_LIB `handle_error` (COMPONENT_MAPPING.md §7,
 * error-codes.md §3 invariant 4):
 *   - 401 TOKEN_INVALID is handled by the axios interceptor (refresh once → /login); here it
 *     is silent.
 *   - `field != null` (or a code with a known default field) → inline field error.
 *   - otherwise → toast of `message` (+ traceId so support can join `error_log`).
 */

export interface HandledError {
  code: string;
  message: string;
  traceId?: string;
  /** Field-level errors keyed by the request property name. */
  fieldErrors: Record<string, string>;
  /** True when the error was surfaced as a toast rather than inline. */
  toasted: boolean;
}

const FALLBACK_MESSAGE = 'Something went wrong. Please try again.';

export function normaliseError(error: unknown): { apiError: ApiError | null; status: number | null } {
  if (isApiError(error)) return { apiError: error.response.data, status: error.response.status };
  return { apiError: null, status: null };
}

export function useErrorHandler() {
  const { push } = useToast();

  const handleError = useCallback(
    (error: unknown, opts: { fieldNames?: readonly string[]; toast?: boolean } = {}): HandledError => {
      const allowToast = opts.toast ?? true;
      const { apiError, status } = normaliseError(error);

      if (!apiError) {
        if (allowToast) push({ kind: 'error', message: FALLBACK_MESSAGE });
        return { code: FrameworkErrorCode.INTERNAL_ERROR, message: FALLBACK_MESSAGE, fieldErrors: {}, toasted: allowToast };
      }

      if (status === 401 && apiError.code === FrameworkErrorCode.TOKEN_INVALID) {
        return { code: apiError.code, message: apiError.message, traceId: apiError.traceId, fieldErrors: {}, toasted: false };
      }

      const fieldErrors: Record<string, string> = {};
      const field = apiError.field ?? DEFAULT_FIELD_FOR_CODE[apiError.code];
      const fieldAccepted = (name: string) => !opts.fieldNames || opts.fieldNames.includes(name);

      if (apiError.code === FrameworkErrorCode.VALIDATION_FAILED && apiError.details?.length) {
        for (const d of apiError.details) if (fieldAccepted(d.field) && !(d.field in fieldErrors)) fieldErrors[d.field] = d.message;
      }
      if (field && fieldAccepted(field) && !(field in fieldErrors)) fieldErrors[field] = apiError.message;

      const toasted = Object.keys(fieldErrors).length === 0 && allowToast;
      if (toasted) {
        const message = KNOWN_ERROR_CODES.has(apiError.code) || apiError.code.startsWith('-20') ? apiError.message : FALLBACK_MESSAGE;
        push({ kind: 'error', message, traceId: apiError.traceId });
      }

      return { code: apiError.code, message: apiError.message, traceId: apiError.traceId, fieldErrors, toasted };
    },
    [push],
  );

  return { handleError };
}
