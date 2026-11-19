// Shared HTTP client for /api/v1 (openapi-v1.yaml). Feature folders add their own typed calls on top.
export interface ApiErrorBody {
  code: string;
  message: string;
  details?: string[];
}

export class ApiError extends Error {
  readonly code: string;
  readonly status: number;
  readonly details: string[];

  constructor(status: number, body: ApiErrorBody) {
    super(body.message);
    this.status = status;
    this.code = body.code;
    this.details = body.details ?? [];
  }
}

const TOKEN_KEY = 'hrms.token';

export const tokenStore = {
  get: (): string | null => window.localStorage.getItem(TOKEN_KEY),
  set: (token: string) => window.localStorage.setItem(TOKEN_KEY, token),
  clear: () => window.localStorage.removeItem(TOKEN_KEY),
};

let unauthorizedHandler: (() => void) | null = null;

export function onUnauthorized(handler: (() => void) | null) {
  unauthorizedHandler = handler;
}

export async function api<T>(path: string, init: { method?: string; body?: unknown } = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  const token = tokenStore.get();
  if (token) headers.Authorization = `Bearer ${token}`;
  if (init.body !== undefined) headers['Content-Type'] = 'application/json';
  const response = await fetch(`/api/v1${path}`, {
    method: init.method ?? 'GET',
    headers,
    body: init.body === undefined ? undefined : JSON.stringify(init.body),
  });
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  const data = text ? JSON.parse(text) : undefined;
  if (!response.ok) {
    if (response.status === 401 && path !== '/auth/login') unauthorizedHandler?.();
    throw new ApiError(response.status, data ?? { code: String(response.status), message: response.statusText });
  }
  return data as T;
}
