import axios, { AxiosError, type AxiosRequestConfig, type InternalAxiosRequestConfig } from 'axios';
import type { ApiError, TokenResponse } from './types';

/**
 * Single axios instance for every contract call. The access token lives only in memory
 * (never localStorage / URL – COMPONENT_MAPPING.md §1); the refresh token is the HttpOnly
 * `hrms_refresh` cookie the browser sends on `/api/auth/refresh` by itself.
 *
 * 401 handling (error-codes.md §3 invariant 4, COMPONENT_MAPPING.md §7 `check_session`):
 * one silent `POST /api/auth/refresh`, replay the original request, otherwise hand over to
 * `onSessionExpired` (AuthContext → `/login` with the "Session has expired" message).
 */

type RetriableConfig = InternalAxiosRequestConfig & { _retried?: boolean };

let accessToken: string | null = null;
let onTokenRefreshed: ((t: TokenResponse) => void) | null = null;
let onSessionExpired: (() => void) | null = null;
let refreshInFlight: Promise<TokenResponse> | null = null;

export function setAccessToken(token: string | null): void {
  accessToken = token;
}

export function getAccessToken(): string | null {
  return accessToken;
}

export function bindSessionHandlers(handlers: {
  onTokenRefreshed: (t: TokenResponse) => void;
  onSessionExpired: () => void;
}): void {
  onTokenRefreshed = handlers.onTokenRefreshed;
  onSessionExpired = handlers.onSessionExpired;
}

export const http = axios.create({
  baseURL: '/',
  withCredentials: true,
  headers: { Accept: 'application/json' },
});

http.interceptors.request.use((config) => {
  if (accessToken && !config.headers.Authorization) {
    config.headers.Authorization = `Bearer ${accessToken}`;
  }
  return config;
});

const REFRESH_URL = '/api/auth/refresh';
const LOGIN_URL = '/api/auth/login';

function isAuthEndpoint(url: string | undefined): boolean {
  return url === REFRESH_URL || url === LOGIN_URL;
}

export async function refreshAccessToken(): Promise<TokenResponse> {
  if (!refreshInFlight) {
    refreshInFlight = axios
      .post<TokenResponse>(REFRESH_URL, undefined, { withCredentials: true })
      .then((r) => {
        setAccessToken(r.data.accessToken);
        onTokenRefreshed?.(r.data);
        return r.data;
      })
      .finally(() => {
        refreshInFlight = null;
      });
  }
  return refreshInFlight;
}

http.interceptors.response.use(undefined, async (error: AxiosError<ApiError>) => {
  const config = error.config as RetriableConfig | undefined;
  if (error.response?.status === 401 && config && !config._retried && !isAuthEndpoint(config.url)) {
    config._retried = true;
    try {
      const refreshed = await refreshAccessToken();
      config.headers.Authorization = `Bearer ${refreshed.accessToken}`;
      return http.request(config);
    } catch {
      setAccessToken(null);
      onSessionExpired?.();
    }
  }
  return Promise.reject(error);
});

export function isApiError(e: unknown): e is AxiosError<ApiError> & { response: { data: ApiError } } {
  return (
    axios.isAxiosError(e) &&
    typeof e.response?.data === 'object' &&
    e.response?.data !== null &&
    typeof (e.response.data as ApiError).code === 'string'
  );
}

export type RequestConfig = AxiosRequestConfig;
