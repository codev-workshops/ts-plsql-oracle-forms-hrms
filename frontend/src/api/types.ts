/**
 * Hand-written 1:1 TypeScript projection of contracts/p0-foundation/openapi.yaml
 * (components.schemas). Do not add fields that are not in the contract.
 */

export interface ApiErrorDetail {
  field: string;
  code: string;
  message: string;
}

export interface ApiError {
  /** Legacy `-20xxx` code (string, verbatim) or UPPER_SNAKE_CASE framework code. */
  code: string;
  message: string;
  field?: string;
  traceId: string;
  details?: ApiErrorDetail[];
}

export interface LoginRequest {
  username: string;
  password: string;
}

export type Authority =
  `${'PAYROLL' | 'EMPLOYEE' | 'LEAVE' | 'ADMIN' | 'REPORTS'}:${'VIEW' | 'EDIT' | 'APPROVE' | 'CREATE'}`;

export interface CurrentUser {
  userId: string;
  empId: number;
  empNumber: string;
  email: string;
  firstName: string;
  lastName: string;
  displayName: string;
  deptId?: number | null;
  jobTitle?: string | null;
  roles: Authority[];
  mustChangePassword: boolean;
}

export interface TokenResponse {
  accessToken: string;
  tokenType: 'Bearer';
  expiresIn: number;
  user: CurrentUser;
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}

export interface DepartmentRef {
  deptId: number;
  deptCode: string;
  deptName: string;
  parentDeptId?: number | null;
  locationCode?: string | null;
  active: boolean;
}

export interface JobTitleRef {
  jobId: number;
  jobCode: string;
  jobTitle: string;
  jobFamily?: string | null;
  gradeId: number;
  gradeCode: string;
  gradeName: string;
  gradeMinSalary: string;
  gradeMaxSalary: string;
  active: boolean;
}

export interface LocationRef {
  locationCode: string;
  locationName: string;
  city?: string | null;
  stateProvince?: string | null;
  countryCode?: string | null;
  timezone: string;
  active: boolean;
}

export interface LeaveTypeRef {
  leaveTypeId: number;
  leaveTypeCode: string;
  leaveTypeName: string;
  paid: boolean;
  accrual: boolean;
  accrualRate?: string | null;
  maxBalance?: string | null;
  carryoverMax?: string | null;
  minTenureDays: number;
  requiresApproval: boolean;
  requiresDocument: boolean;
  active: boolean;
}

export interface EmployeeSummary {
  id: number;
  empNumber: string;
  name: string;
  jobTitle: string | null;
}

export interface PageOfEmployeeSummary {
  content: EmployeeSummary[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export type ProxyModule =
  | 'auth'
  | 'employee'
  | 'payroll'
  | 'payroll.engine'
  | 'leave'
  | 'performance'
  | 'reporting';

export interface SsoExchangeRequest {
  module: ProxyModule;
  clientIp: string;
  returnPath?: string;
}

export interface SsoExchangeResponse {
  formsModule: 'HRMS_EMPLOYEE' | 'HRMS_PAYROLL' | 'HRMS_LEAVE' | 'HRMS_PERFORMANCE' | 'HRMS_MENU';
  otherparams: string;
  legacySessionId: number;
  expiresAt: string;
}

export interface EmployeeSearchQuery {
  status: 'ACTIVE';
  fields: 'id,name,jobTitle';
  q?: string;
  excludeSelf?: boolean;
  page?: number;
  size?: number;
}

export interface ReferenceQuery {
  active?: boolean;
}
