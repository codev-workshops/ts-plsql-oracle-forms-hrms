import createClient, { type Middleware } from 'openapi-fetch';
import type { components, paths } from './schema';

export type Schemas = components['schemas'];

/** RFC 9457 problem body returned by every error response (D-05). */
export interface Problem {
  status?: number;
  title?: string;
  detail?: string;
  code?: string;
  legacyCode?: number | null;
  traceId?: string;
  errors?: { field?: string; message?: string }[];
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly problem: Problem,
  ) {
    super(problem.detail ?? problem.title ?? `HTTP ${status}`);
  }

  get code(): string | undefined {
    return this.problem.code;
  }
}

const TOKEN_KEY = 'hrms.token';
let token: string | null = sessionStorage.getItem(TOKEN_KEY);
let onUnauthenticated: (() => void) | null = null;

export function setToken(value: string | null) {
  token = value;
  if (value) sessionStorage.setItem(TOKEN_KEY, value);
  else sessionStorage.removeItem(TOKEN_KEY);
}

export function getToken() {
  return token;
}

export function onSessionExpired(handler: (() => void) | null) {
  onUnauthenticated = handler;
}

const auth: Middleware = {
  onRequest({ request }) {
    if (token) request.headers.set('Authorization', `Bearer ${token}`);
    return request;
  },
  onResponse({ response, request }) {
    if (response.status === 401 && token && !request.url.endsWith('/api/v1/auth/login')) {
      setToken(null);
      onUnauthenticated?.();
    }
    return response;
  },
};

export const api = createClient<paths>({
  baseUrl: globalThis.location?.origin ?? '',
  fetch: (request: Request) => globalThis.fetch(request),
});
api.use(auth);

/** Unwraps an openapi-fetch result, throwing {@link ApiError} with the problem body on failure. */
export async function call<T>(
  promise: Promise<{ data?: T; error?: unknown; response: Response }>,
): Promise<T> {
  const { data, error, response } = await promise;
  if (!response.ok) {
    throw new ApiError(response.status, (error ?? {}) as Problem);
  }
  return data as T;
}

const MESSAGES: Record<string, string> = {
  INVALID_CREDENTIALS: 'Invalid email or password. Repeated failures lock the account for 15 minutes.',
  UNAUTHENTICATED: 'Your session has expired. Please sign in again.',
  FORBIDDEN: 'You do not have permission for this action.',
  INVALID_CURRENT_PASSWORD: 'The current password is incorrect.',
  PASSWORD_POLICY: 'The new password does not meet the password policy.',
};

export function errorMessage(e: unknown): string {
  if (e instanceof ApiError) {
    const base = (e.code && MESSAGES[e.code]) || e.message;
    const fields = e.problem.errors?.map((f) => `${f.field ?? ''}: ${f.message ?? ''}`).join('; ');
    const code = e.code ? ` [${e.code}${e.problem.legacyCode ? ` ${e.problem.legacyCode}` : ''}]` : '';
    return `${base}${fields ? ` (${fields})` : ''}${code}`;
  }
  return e instanceof Error ? e.message : String(e);
}
