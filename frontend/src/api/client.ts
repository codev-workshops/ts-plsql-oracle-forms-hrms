import { http, setAccessToken } from './http';
import type {
  ChangePasswordRequest,
  CurrentUser,
  DepartmentRef,
  EmployeeSearchQuery,
  JobTitleRef,
  LeaveTypeRef,
  LocationRef,
  LoginRequest,
  PageOfEmployeeSummary,
  ReferenceQuery,
  TokenResponse,
} from './types';

/**
 * One function per operationId in contracts/p0-foundation/openapi.yaml (eleven operations).
 * `exchangeJwtForFormsSession` is proxy-only and intentionally has no browser client.
 */
export const api = {
  auth: {
    async login(body: LoginRequest): Promise<TokenResponse> {
      const { data } = await http.post<TokenResponse>('/api/auth/login', body);
      setAccessToken(data.accessToken);
      return data;
    },
    async logout(): Promise<void> {
      try {
        await http.post('/api/auth/logout');
      } finally {
        setAccessToken(null);
      }
    },
    async refresh(): Promise<TokenResponse> {
      const { data } = await http.post<TokenResponse>('/api/auth/refresh');
      setAccessToken(data.accessToken);
      return data;
    },
    async me(): Promise<CurrentUser> {
      const { data } = await http.get<CurrentUser>('/api/auth/me');
      return data;
    },
    async changePassword(body: ChangePasswordRequest): Promise<void> {
      await http.put('/api/auth/password', body);
    },
  },
  reference: {
    async listDepartments(params: ReferenceQuery = {}): Promise<DepartmentRef[]> {
      const { data } = await http.get<DepartmentRef[]>('/api/reference/departments', { params });
      return data;
    },
    async listJobTitles(params: ReferenceQuery = {}): Promise<JobTitleRef[]> {
      const { data } = await http.get<JobTitleRef[]>('/api/reference/job-titles', { params });
      return data;
    },
    async listLocations(params: ReferenceQuery = {}): Promise<LocationRef[]> {
      const { data } = await http.get<LocationRef[]>('/api/reference/locations', { params });
      return data;
    },
    async listLeaveTypes(params: ReferenceQuery = {}): Promise<LeaveTypeRef[]> {
      const { data } = await http.get<LeaveTypeRef[]>('/api/reference/leave-types', { params });
      return data;
    },
  },
  employees: {
    async searchEmployees(params: EmployeeSearchQuery): Promise<PageOfEmployeeSummary> {
      const { data } = await http.get<PageOfEmployeeSummary>('/api/employees', { params });
      return data;
    },
  },
};

export type Api = typeof api;
