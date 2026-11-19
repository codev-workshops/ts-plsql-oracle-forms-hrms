import { api } from '../../api/client';

export type EmploymentStatus = 'ACTIVE' | 'ON_LEAVE' | 'SUSPENDED' | 'TERMINATED';

export interface EmployeeSummary {
  empId: number;
  empNumber: string;
  firstName: string;
  lastName: string;
  fullName: string;
  email?: string | null;
  deptId: number;
  deptName: string;
  jobId: number;
  jobTitle: string;
  gradeLevel: number;
  managerEmpId?: number | null;
  locationCode?: string | null;
  hireDate: string;
  employmentStatus: EmploymentStatus;
  active: boolean;
}

export interface SalaryRecord {
  salaryId: number;
  empId: number;
  effectiveDate: string;
  endDate?: string | null;
  baseSalary: number;
  currencyCode: string;
  payFrequency: string;
  salaryBasis: string;
  changeReason?: string | null;
  changePct?: number | null;
  active: boolean;
}

export interface EmployeeDetail extends EmployeeSummary {
  middleName?: string | null;
  dateOfBirth?: string | null;
  gender?: string | null;
  maritalStatus?: string | null;
  phoneWork?: string | null;
  phoneMobile?: string | null;
  addressLine1?: string | null;
  addressLine2?: string | null;
  city?: string | null;
  state?: string | null;
  postalCode?: string | null;
  country?: string | null;
  locationId?: number | null;
  employmentType?: string | null;
  terminationDate?: string | null;
  terminationReason?: string | null;
  tenureYears?: number;
  currentSalary?: SalaryRecord | null;
  warnings?: string[];
}

export interface EmployeeCreate {
  firstName: string;
  lastName: string;
  email: string;
  hireDate: string;
  deptId: number;
  jobId: number;
  middleName?: string;
  phoneWork?: string;
  phoneMobile?: string;
  dateOfBirth?: string;
  gender?: string;
  managerEmpId?: number;
  locationId?: number;
  employmentType?: string;
  baseSalary?: number;
}

export type EmployeeUpdate = Partial<Pick<EmployeeDetail, 'firstName' | 'middleName' | 'lastName' | 'email' |
  'phoneWork' | 'phoneMobile' | 'addressLine1' | 'addressLine2' | 'city' | 'state' | 'postalCode' | 'country'>>;

export interface TransferRequest { deptId: number; jobId?: number; locationId?: number; managerEmpId?: number;
  effectiveDate: string; reason?: string }
export interface PromoteRequest { jobId: number; newSalary: number; effectiveDate: string; reason?: string }
export interface TerminateRequest { terminationDate: string; reason: string }
export interface RehireRequest { hireDate: string; deptId: number; jobId: number; managerEmpId?: number;
  baseSalary?: number }

export interface OrgChartNode { empId: number; fullName: string; jobTitle: string; managerEmpId?: number | null;
  level: number }

export interface EmployeeHistory {
  historyId: number;
  changeType: string;
  effectiveDate: string;
  oldValues: Record<string, unknown>;
  newValues: Record<string, unknown>;
  reason?: string | null;
  createdBy: string;
  createdDate: string;
}

export interface Dependent {
  dependentId?: number;
  firstName: string;
  lastName: string;
  relationship: string;
  dateOfBirth?: string | null;
  gender?: string | null;
  benefitsEligible?: boolean;
}

export interface EmergencyContact {
  contactId?: number;
  contactName: string;
  relationship?: string | null;
  phonePrimary: string;
  phoneSecondary?: string | null;
  email?: string | null;
  isPrimary?: boolean;
}

export interface RefItem { id: number; code: string; name: string; gradeId?: number }
export interface HeadcountRow { deptId: number; deptName: string; headcount: number }

export interface SearchFilter {
  lastName?: string;
  firstName?: string;
  deptId?: string;
  locationId?: string;
  status?: string;
  hiredFrom?: string;
  hiredTo?: string;
  limit?: number;
}

function query(params: object): string {
  const q = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') q.set(k, String(v));
  });
  const s = q.toString();
  return s ? `?${s}` : '';
}

const base = (empId: number) => `/employees/${empId}`;

export const employeeApi = {
  search: (f: SearchFilter) => api<EmployeeSummary[]>(`/employees${query(f)}`),
  get: (empId: number) => api<EmployeeDetail>(base(empId)),
  byNumber: (empNumber: string) => api<EmployeeDetail>(`/employees/by-number/${encodeURIComponent(empNumber)}`),
  create: (body: EmployeeCreate) => api<EmployeeDetail>('/employees', { method: 'POST', body }),
  update: (empId: number, body: EmployeeUpdate) => api<EmployeeDetail>(base(empId), { method: 'PATCH', body }),
  transfer: (empId: number, body: TransferRequest) =>
    api<EmployeeDetail>(`${base(empId)}/transfer`, { method: 'POST', body }),
  promote: (empId: number, body: PromoteRequest) =>
    api<EmployeeDetail>(`${base(empId)}/promote`, { method: 'POST', body }),
  terminate: (empId: number, body: TerminateRequest) =>
    api<EmployeeDetail>(`${base(empId)}/terminate`, { method: 'POST', body }),
  rehire: (empId: number, body: RehireRequest) =>
    api<EmployeeDetail>(`${base(empId)}/rehire`, { method: 'POST', body }),
  directReports: (empId: number) => api<EmployeeSummary[]>(`${base(empId)}/direct-reports`),
  orgChart: (empId: number) => api<OrgChartNode[]>(`${base(empId)}/org-chart`),
  history: (empId: number) => api<EmployeeHistory[]>(`${base(empId)}/history`),
  salaryHistory: (empId: number) => api<SalaryRecord[]>(`${base(empId)}/salary-history`),
  dependents: (empId: number) => api<Dependent[]>(`${base(empId)}/dependents`),
  addDependent: (empId: number, body: Dependent) =>
    api<Dependent>(`${base(empId)}/dependents`, { method: 'POST', body }),
  updateDependent: (empId: number, id: number, body: Dependent) =>
    api<Dependent>(`${base(empId)}/dependents/${id}`, { method: 'PUT', body }),
  deleteDependent: (empId: number, id: number) =>
    api<void>(`${base(empId)}/dependents/${id}`, { method: 'DELETE' }),
  emergencyContacts: (empId: number) => api<EmergencyContact[]>(`${base(empId)}/emergency-contacts`),
  addEmergencyContact: (empId: number, body: EmergencyContact) =>
    api<EmergencyContact>(`${base(empId)}/emergency-contacts`, { method: 'POST', body }),
  updateEmergencyContact: (empId: number, id: number, body: EmergencyContact) =>
    api<EmergencyContact>(`${base(empId)}/emergency-contacts/${id}`, { method: 'PUT', body }),
  deleteEmergencyContact: (empId: number, id: number) =>
    api<void>(`${base(empId)}/emergency-contacts/${id}`, { method: 'DELETE' }),
  departments: () => api<RefItem[]>('/departments'),
  headcount: (asOf?: string) => api<HeadcountRow[]>(`/departments/headcount${query({ asOf })}`),
  locations: () => api<RefItem[]>('/locations'),
  jobTitles: () => api<RefItem[]>('/job-titles'),
};
