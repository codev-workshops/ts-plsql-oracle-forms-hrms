import { http, setAccessToken } from './http';
import type {
  ChangePasswordRequest,
  CurrentUser,
  DepartmentRef,
  Dependent,
  DependentRequest,
  EmergencyContact,
  EmergencyContactRequest,
  EmployeeCreateRequest,
  EmployeeDetail,
  EmployeeDetailWithEtag,
  EmployeeHistoryEntry,
  EmployeeListQuery,
  EmployeeSearchQuery,
  EmployeeTerminateRequest,
  EmployeeTransferRequest,
  EmployeeUpdateRequest,
  PageOfEmployeeListItem,
  SalaryChangeRequest,
  SalaryRecord,
  JobTitleRef,
  LeaveTypeRef,
  LocationRef,
  LoginRequest,
  PageOfEmployeeSummary,
  PageOfPerformanceReview,
  AcknowledgeRequest,
  GenerateReviewsResult,
  GoalProgressRequest,
  GoalRequest,
  PerformanceGoal,
  PerformanceReview,
  RatingDistributionRow,
  ReviewCycle,
  ReviewCycleRequest,
  SelfAssessmentRequest,
  TeamReviewRow,
  ListCyclesQuery,
  ListCycleReviewsQuery,
  ManagerReviewRequest,
  ReferenceQuery,
  TokenResponse,
  BusinessDays,
  LeaveApproveRequest,
  LeaveBalance,
  LeaveCancelRequest,
  LeaveRejectRequest,
  LeaveRequest,
  LeaveRequestCreateRequest,
  LeaveRequestFilter,
  PageOfPayPeriod,
  PageOfPayrollDetail,
  PayPeriod,
  PayPeriodListQuery,
  PayrollDetailListQuery,
  PayrollRegisterDownload,
  PayrollRun,
  PayrollRunApproval,
  PayrollRunCreateRequest,
  PayrollRunListQuery,
  PayrollRunReverseRequest,
  PayrollRunStatus,
  Payslip,
  ShadowDiffReport,
  LeaveRequestsForEmployeeQuery,
  PageOfLeaveRequest,
  PendingLeaveApproval,
  TeamCalendarEntry,
  AccrualRunRequest,
  AuditLogPage,
  AuditLogSearchQuery,
  BatchRunResult,
  BenefitsFeedRequest,
  CarryoverRunRequest,
  CsvDownload,
  Department,
  DepartmentRequest,
  EmployeeCompensationPage,
  EmployeeCompensationQuery,
  EmployeeDirectoryPage,
  EmployeeDirectoryQuery,
  GlFeedRequest,
  IntegrationFile,
  IntegrationFileListQuery,
  IntegrationFilePage,
  IntegrationStatus,
  JobGrade,
  JobGradeRequest,
  JobTitle,
  JobTitleRequest,
  LeaveSummaryPage,
  LeaveSummaryQuery,
  LeaveType,
  LeaveTypeRequest,
  Location,
  LocationRequest,
  OrgHierarchyPage,
  OrgHierarchyQuery,
  PayrollLatestPage,
  PayrollLatestQuery,
  PendingApprovalPage,
  PendingApprovalsQuery,
  SystemParameter,
  SystemParameterRequest,
  SystemParameterUpdateRequest,
  TimeAttendanceImportResult,
} from './types';

/** `GET …csv` twin of a paged report: `Accept: text/csv`, body streamed as text, filename from `Content-Disposition`. */
async function downloadCsv(path: string, params: object, fallbackName: string): Promise<CsvDownload> {
  const res = await http.get<string>(path, {
    params,
    headers: { Accept: 'text/csv' },
    responseType: 'text',
    transformResponse: (d: string) => d,
  });
  const disposition = (res.headers as Record<string, string | undefined>)['content-disposition'] ?? '';
  const filename = /filename="([^"]+)"/.exec(disposition)?.[1] ?? fallbackName;
  return { filename, csv: res.data };
}

/**
 * One function per operationId in contracts/p0-foundation/openapi.yaml,
 * contracts/p1-performance/openapi.yaml, contracts/p2-leave/openapi.yaml,
 * contracts/p3-employee/openapi.yaml, contracts/p4-payroll/openapi.yaml and
 * contracts/p5-reporting-decommission/openapi.yaml (the P2 `x-deferred` admin routes are
 * mounted in P5 under `/api/admin/leave/**`; the `301` aliases have no client).
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
    // --- contracts/p3-employee/openapi.yaml, employee-service ---------------------------
    async listEmployees(params: EmployeeListQuery = {}): Promise<PageOfEmployeeListItem> {
      const { data } = await http.get<PageOfEmployeeListItem>('/api/employees', { params });
      return data;
    },
    async createEmployee(body: EmployeeCreateRequest): Promise<EmployeeDetail> {
      const { data } = await http.post<EmployeeDetail>('/api/employees', body);
      return data;
    },
    async getEmployee(id: number): Promise<EmployeeDetailWithEtag> {
      const res = await http.get<EmployeeDetail>(`/api/employees/${id}`);
      const etag = (res.headers as Record<string, string | undefined>).etag ?? `"${res.data.version}"`;
      return { employee: res.data, etag };
    },
    async updateEmployee(id: number, etag: string, body: EmployeeUpdateRequest): Promise<EmployeeDetailWithEtag> {
      const res = await http.put<EmployeeDetail>(`/api/employees/${id}`, body, { headers: { 'If-Match': etag } });
      const next = (res.headers as Record<string, string | undefined>).etag ?? `"${res.data.version}"`;
      return { employee: res.data, etag: next };
    },
    async terminateEmployee(id: number, body: EmployeeTerminateRequest): Promise<EmployeeDetail> {
      const { data } = await http.post<EmployeeDetail>(`/api/employees/${id}/terminate`, body);
      return data;
    },
    async transferEmployee(id: number, body: EmployeeTransferRequest): Promise<EmployeeDetail> {
      const { data } = await http.post<EmployeeDetail>(`/api/employees/${id}/transfer`, body);
      return data;
    },
    async listEmployeeHistory(id: number): Promise<EmployeeHistoryEntry[]> {
      const { data } = await http.get<EmployeeHistoryEntry[]>(`/api/employees/${id}/history`);
      return data;
    },
    async listDependents(id: number): Promise<Dependent[]> {
      const { data } = await http.get<Dependent[]>(`/api/employees/${id}/dependents`);
      return data;
    },
    async addDependent(id: number, body: DependentRequest): Promise<Dependent> {
      const { data } = await http.post<Dependent>(`/api/employees/${id}/dependents`, body);
      return data;
    },
    async updateDependent(id: number, dependentId: number, body: DependentRequest): Promise<Dependent> {
      const { data } = await http.put<Dependent>(`/api/employees/${id}/dependents/${dependentId}`, body);
      return data;
    },
    async listEmergencyContacts(id: number): Promise<EmergencyContact[]> {
      const { data } = await http.get<EmergencyContact[]>(`/api/employees/${id}/contacts`);
      return data;
    },
    async addEmergencyContact(id: number, body: EmergencyContactRequest): Promise<EmergencyContact> {
      const { data } = await http.post<EmergencyContact>(`/api/employees/${id}/contacts`, body);
      return data;
    },
    async updateEmergencyContact(id: number, contactId: number, body: EmergencyContactRequest): Promise<EmergencyContact> {
      const { data } = await http.put<EmergencyContact>(`/api/employees/${id}/contacts/${contactId}`, body);
      return data;
    },
  },
  /** `salary-module` owns `/api/employees/{id}/salary/**` (ARCH-01) – kept apart from `employees`. */
  salary: {
    async getCurrentSalary(empId: number): Promise<SalaryRecord> {
      const { data } = await http.get<SalaryRecord>(`/api/employees/${empId}/salary`);
      return data;
    },
    async changeSalary(empId: number, body: SalaryChangeRequest): Promise<SalaryRecord> {
      const { data } = await http.post<SalaryRecord>(`/api/employees/${empId}/salary`, body);
      return data;
    },
    async listSalaryHistory(empId: number): Promise<SalaryRecord[]> {
      const { data } = await http.get<SalaryRecord[]>(`/api/employees/${empId}/salary/history`);
      return data;
    },
  },
  performance: {
    async listCycles(params: ListCyclesQuery = {}): Promise<ReviewCycle[]> {
      const { data } = await http.get<ReviewCycle[]>('/api/performance/cycles', { params });
      return data;
    },
    async createCycle(body: ReviewCycleRequest): Promise<ReviewCycle> {
      const { data } = await http.post<ReviewCycle>('/api/performance/cycles', body);
      return data;
    },
    async getCycle(cycleId: number): Promise<ReviewCycle> {
      const { data } = await http.get<ReviewCycle>(`/api/performance/cycles/${cycleId}`);
      return data;
    },
    async updateCycle(cycleId: number, body: ReviewCycleRequest): Promise<ReviewCycle> {
      const { data } = await http.put<ReviewCycle>(`/api/performance/cycles/${cycleId}`, body);
      return data;
    },
    async openCycle(cycleId: number): Promise<ReviewCycle> {
      const { data } = await http.post<ReviewCycle>(`/api/performance/cycles/${cycleId}/open`);
      return data;
    },
    async closeCycle(cycleId: number): Promise<ReviewCycle> {
      const { data } = await http.post<ReviewCycle>(`/api/performance/cycles/${cycleId}/close`);
      return data;
    },
    async generateReviews(cycleId: number): Promise<GenerateReviewsResult> {
      const { data } = await http.post<GenerateReviewsResult>(`/api/performance/cycles/${cycleId}/generate-reviews`);
      return data;
    },
    async listCycleReviews(cycleId: number, params: ListCycleReviewsQuery = {}): Promise<PageOfPerformanceReview> {
      const { data } = await http.get<PageOfPerformanceReview>(`/api/performance/cycles/${cycleId}/reviews`, { params });
      return data;
    },
    async listMyReviews(params: { cycleId?: number } = {}): Promise<PerformanceReview[]> {
      const { data } = await http.get<PerformanceReview[]>('/api/performance/reviews/mine', { params });
      return data;
    },
    async getReview(reviewId: number): Promise<PerformanceReview> {
      const { data } = await http.get<PerformanceReview>(`/api/performance/reviews/${reviewId}`);
      return data;
    },
    async submitSelfAssessment(reviewId: number, body: SelfAssessmentRequest): Promise<PerformanceReview> {
      const { data } = await http.post<PerformanceReview>(`/api/performance/reviews/${reviewId}/self-assessment`, body);
      return data;
    },
    async submitManagerReview(reviewId: number, body: ManagerReviewRequest): Promise<PerformanceReview> {
      const { data } = await http.post<PerformanceReview>(`/api/performance/reviews/${reviewId}/manager-review`, body);
      return data;
    },
    async acknowledgeReview(reviewId: number, body?: AcknowledgeRequest): Promise<PerformanceReview> {
      const { data } = await http.post<PerformanceReview>(`/api/performance/reviews/${reviewId}/acknowledge`, body);
      return data;
    },
    async listGoals(reviewId: number): Promise<PerformanceGoal[]> {
      const { data } = await http.get<PerformanceGoal[]>(`/api/performance/reviews/${reviewId}/goals`);
      return data;
    },
    async addGoal(reviewId: number, body: GoalRequest): Promise<PerformanceGoal> {
      const { data } = await http.post<PerformanceGoal>(`/api/performance/reviews/${reviewId}/goals`, body);
      return data;
    },
    async updateGoalProgress(goalId: number, body: GoalProgressRequest): Promise<PerformanceGoal> {
      const { data } = await http.patch<PerformanceGoal>(`/api/performance/goals/${goalId}/progress`, body);
      return data;
    },
    async listTeamReviews(cycleId: number): Promise<TeamReviewRow[]> {
      const { data } = await http.get<TeamReviewRow[]>(`/api/performance/cycles/${cycleId}/team-reviews`);
      return data;
    },
    async getRatingDistribution(cycleId: number, params: { deptId?: number } = {}): Promise<RatingDistributionRow[]> {
      const { data } = await http.get<RatingDistributionRow[]>(`/api/performance/cycles/${cycleId}/rating-distribution`, { params });
      return data;
    },
  },
  leave: {
    async listMyLeaveRequests(params: LeaveRequestFilter = {}): Promise<LeaveRequest[]> {
      const { data } = await http.get<LeaveRequest[]>('/api/leave/requests/mine', { params });
      return data;
    },
    async listLeaveRequestsForEmployee(params: LeaveRequestsForEmployeeQuery): Promise<PageOfLeaveRequest> {
      const { data } = await http.get<PageOfLeaveRequest>('/api/leave/requests', { params });
      return data;
    },
    async submitLeaveRequest(body: LeaveRequestCreateRequest): Promise<LeaveRequest> {
      const { data } = await http.post<LeaveRequest>('/api/leave/requests', body);
      return data;
    },
    async getLeaveRequest(id: number): Promise<LeaveRequest> {
      const { data } = await http.get<LeaveRequest>(`/api/leave/requests/${id}`);
      return data;
    },
    async cancelLeaveRequest(id: number, body?: LeaveCancelRequest): Promise<LeaveRequest> {
      const { data } = await http.post<LeaveRequest>(`/api/leave/requests/${id}/cancel`, body);
      return data;
    },
    async approveLeaveRequest(id: number, body?: LeaveApproveRequest): Promise<LeaveRequest> {
      const { data } = await http.post<LeaveRequest>(`/api/leave/requests/${id}/approve`, body);
      return data;
    },
    async rejectLeaveRequest(id: number, body: LeaveRejectRequest): Promise<LeaveRequest> {
      const { data } = await http.post<LeaveRequest>(`/api/leave/requests/${id}/reject`, body);
      return data;
    },
    async getMyLeaveBalances(params: { year?: number } = {}): Promise<LeaveBalance[]> {
      const { data } = await http.get<LeaveBalance[]>('/api/leave/balances/mine', { params });
      return data;
    },
    async getBusinessDays(params: { start: string; end: string }): Promise<BusinessDays> {
      const { data } = await http.get<BusinessDays>('/api/leave/business-days', { params });
      return data;
    },
    async listPendingLeaveApprovals(): Promise<PendingLeaveApproval[]> {
      const { data } = await http.get<PendingLeaveApproval[]>('/api/leave/approvals/pending');
      return data;
    },
    async getTeamLeaveCalendar(params: { from: string; to: string }): Promise<TeamCalendarEntry[]> {
      const { data } = await http.get<TeamCalendarEntry[]>('/api/leave/team-calendar', { params });
      return data;
    },
  },

  /** `payroll-module` – contracts/p4-payroll/openapi.yaml, one function per operationId. */
  payroll: {
    async listPayPeriods(params: PayPeriodListQuery = {}): Promise<PageOfPayPeriod> {
      const { data } = await http.get<PageOfPayPeriod>('/api/payroll/periods', { params });
      return data;
    },
    async closePayPeriod(periodId: number): Promise<PayPeriod> {
      const { data } = await http.post<PayPeriod>(`/api/payroll/periods/${periodId}/close`);
      return data;
    },
    async listPayrollRuns(periodId: number, params: PayrollRunListQuery = {}): Promise<PayrollRun[]> {
      const { data } = await http.get<PayrollRun[]>(`/api/payroll/periods/${periodId}/runs`, { params });
      return data;
    },
    async createPayrollRun(periodId: number, body: PayrollRunCreateRequest): Promise<PayrollRun> {
      const { data } = await http.post<PayrollRun>(`/api/payroll/periods/${periodId}/runs`, body);
      return data;
    },
    /** `202 Accepted`; poll `getPayrollRunStatus` every 2 s while `CALCULATING`. */
    async calculatePayrollRun(runId: number): Promise<PayrollRunStatus> {
      const { data } = await http.post<PayrollRunStatus>(`/api/payroll/runs/${runId}/calculate`);
      return data;
    },
    async getPayrollRunStatus(runId: number): Promise<PayrollRunStatus> {
      const { data } = await http.get<PayrollRunStatus>(`/api/payroll/runs/${runId}/status`);
      return data;
    },
    async approvePayrollRun(runId: number): Promise<PayrollRunApproval> {
      const { data } = await http.post<PayrollRunApproval>(`/api/payroll/runs/${runId}/approve`);
      return data;
    },
    async reversePayrollRun(runId: number, body: PayrollRunReverseRequest): Promise<PayrollRun> {
      const { data } = await http.post<PayrollRun>(`/api/payroll/runs/${runId}/reverse`, body);
      return data;
    },
    async listPayrollDetails(runId: number, params: PayrollDetailListQuery = {}): Promise<PageOfPayrollDetail> {
      const { data } = await http.get<PageOfPayrollDetail>(`/api/payroll/runs/${runId}/details`, { params });
      return data;
    },
    async getPayslip(runId: number, empId: number): Promise<Payslip> {
      const { data } = await http.get<Payslip>(`/api/payroll/runs/${runId}/payslips/${empId}`);
      return data;
    },
    async downloadPayrollRegister(runId: number, params: { includeBank?: boolean } = {}): Promise<PayrollRegisterDownload> {
      const res = await http.get<string>(`/api/payroll/runs/${runId}/register.csv`, {
        params,
        headers: { Accept: 'text/csv' },
        responseType: 'text',
        transformResponse: (d: string) => d,
      });
      const disposition = (res.headers as Record<string, string | undefined>)['content-disposition'] ?? '';
      const filename = /filename="([^"]+)"/.exec(disposition)?.[1] ?? `PAY_REGISTER_${runId}.csv`;
      return { filename, csv: res.data };
    },
    async getShadowDiff(runId: number): Promise<ShadowDiffReport> {
      const { data } = await http.get<ShadowDiffReport>(`/api/payroll/shadow/runs/${runId}/diff`);
      return data;
    },
  },
  /** `reporting-module` – contracts/p5-reporting-decommission/openapi.yaml `reports` tag. */
  reports: {
    async employeeDirectory(params: EmployeeDirectoryQuery = {}): Promise<EmployeeDirectoryPage> {
      const { data } = await http.get<EmployeeDirectoryPage>('/api/reports/employee-directory', { params });
      return data;
    },
    employeeDirectoryCsv: (params: EmployeeDirectoryQuery = {}) => downloadCsv('/api/reports/employee-directory.csv', params, 'employee-directory.csv'),
    async orgHierarchy(params: OrgHierarchyQuery = {}): Promise<OrgHierarchyPage> {
      const { data } = await http.get<OrgHierarchyPage>('/api/reports/org-hierarchy', { params });
      return data;
    },
    orgHierarchyCsv: (params: OrgHierarchyQuery = {}) => downloadCsv('/api/reports/org-hierarchy.csv', params, 'org-hierarchy.csv'),
    async employeeCompensation(params: EmployeeCompensationQuery = {}): Promise<EmployeeCompensationPage> {
      const { data } = await http.get<EmployeeCompensationPage>('/api/reports/employee-compensation', { params });
      return data;
    },
    employeeCompensationCsv: (params: EmployeeCompensationQuery = {}) => downloadCsv('/api/reports/employee-compensation.csv', params, 'employee-compensation.csv'),
    async leaveSummary(params: LeaveSummaryQuery = {}): Promise<LeaveSummaryPage> {
      const { data } = await http.get<LeaveSummaryPage>('/api/reports/leave-summary', { params });
      return data;
    },
    leaveSummaryCsv: (params: LeaveSummaryQuery = {}) => downloadCsv('/api/reports/leave-summary.csv', params, 'leave-summary.csv'),
    async payrollLatest(params: PayrollLatestQuery = {}): Promise<PayrollLatestPage> {
      const { data } = await http.get<PayrollLatestPage>('/api/reports/payroll-latest', { params });
      return data;
    },
    payrollLatestCsv: (params: PayrollLatestQuery = {}) => downloadCsv('/api/reports/payroll-latest.csv', params, 'payroll-latest.csv'),
    async pendingApprovals(params: PendingApprovalsQuery = {}): Promise<PendingApprovalPage> {
      const { data } = await http.get<PendingApprovalPage>('/api/reports/pending-approvals', { params });
      return data;
    },
    pendingApprovalsCsv: (params: PendingApprovalsQuery = {}) => downloadCsv('/api/reports/pending-approvals.csv', params, 'pending-approvals.csv'),
  },
  /** `admin-module` – `admin-reference`, `admin-leave-jobs` and `admin-audit` tags. DELETE = soft deactivation (except system parameters). */
  admin: {
    async listDepartments(params: { active?: boolean } = {}): Promise<Department[]> {
      const { data } = await http.get<Department[]>('/api/admin/departments', { params });
      return data;
    },
    async createDepartment(body: DepartmentRequest): Promise<Department> {
      const { data } = await http.post<Department>('/api/admin/departments', body);
      return data;
    },
    async getDepartment(deptId: number): Promise<Department> {
      const { data } = await http.get<Department>(`/api/admin/departments/${deptId}`);
      return data;
    },
    async updateDepartment(deptId: number, body: DepartmentRequest): Promise<Department> {
      const { data } = await http.put<Department>(`/api/admin/departments/${deptId}`, body);
      return data;
    },
    async deactivateDepartment(deptId: number): Promise<void> {
      await http.delete(`/api/admin/departments/${deptId}`);
    },

    async listJobGrades(params: { active?: boolean } = {}): Promise<JobGrade[]> {
      const { data } = await http.get<JobGrade[]>('/api/admin/job-grades', { params });
      return data;
    },
    async createJobGrade(body: JobGradeRequest): Promise<JobGrade> {
      const { data } = await http.post<JobGrade>('/api/admin/job-grades', body);
      return data;
    },
    async getJobGrade(gradeId: number): Promise<JobGrade> {
      const { data } = await http.get<JobGrade>(`/api/admin/job-grades/${gradeId}`);
      return data;
    },
    async updateJobGrade(gradeId: number, body: JobGradeRequest): Promise<JobGrade> {
      const { data } = await http.put<JobGrade>(`/api/admin/job-grades/${gradeId}`, body);
      return data;
    },
    async deactivateJobGrade(gradeId: number): Promise<void> {
      await http.delete(`/api/admin/job-grades/${gradeId}`);
    },

    async listJobTitles(params: { active?: boolean } = {}): Promise<JobTitle[]> {
      const { data } = await http.get<JobTitle[]>('/api/admin/job-titles', { params });
      return data;
    },
    async createJobTitle(body: JobTitleRequest): Promise<JobTitle> {
      const { data } = await http.post<JobTitle>('/api/admin/job-titles', body);
      return data;
    },
    async getJobTitle(jobId: number): Promise<JobTitle> {
      const { data } = await http.get<JobTitle>(`/api/admin/job-titles/${jobId}`);
      return data;
    },
    async updateJobTitle(jobId: number, body: JobTitleRequest): Promise<JobTitle> {
      const { data } = await http.put<JobTitle>(`/api/admin/job-titles/${jobId}`, body);
      return data;
    },
    async deactivateJobTitle(jobId: number): Promise<void> {
      await http.delete(`/api/admin/job-titles/${jobId}`);
    },

    async listLocations(params: { active?: boolean } = {}): Promise<Location[]> {
      const { data } = await http.get<Location[]>('/api/admin/locations', { params });
      return data;
    },
    async createLocation(body: LocationRequest): Promise<Location> {
      const { data } = await http.post<Location>('/api/admin/locations', body);
      return data;
    },
    async getLocation(locationCode: string): Promise<Location> {
      const { data } = await http.get<Location>(`/api/admin/locations/${encodeURIComponent(locationCode)}`);
      return data;
    },
    async updateLocation(locationCode: string, body: LocationRequest): Promise<Location> {
      const { data } = await http.put<Location>(`/api/admin/locations/${encodeURIComponent(locationCode)}`, body);
      return data;
    },
    async deactivateLocation(locationCode: string): Promise<void> {
      await http.delete(`/api/admin/locations/${encodeURIComponent(locationCode)}`);
    },

    async listLeaveTypes(params: { active?: boolean } = {}): Promise<LeaveType[]> {
      const { data } = await http.get<LeaveType[]>('/api/admin/leave-types', { params });
      return data;
    },
    async createLeaveType(body: LeaveTypeRequest): Promise<LeaveType> {
      const { data } = await http.post<LeaveType>('/api/admin/leave-types', body);
      return data;
    },
    async getLeaveType(leaveTypeId: number): Promise<LeaveType> {
      const { data } = await http.get<LeaveType>(`/api/admin/leave-types/${leaveTypeId}`);
      return data;
    },
    async updateLeaveType(leaveTypeId: number, body: LeaveTypeRequest): Promise<LeaveType> {
      const { data } = await http.put<LeaveType>(`/api/admin/leave-types/${leaveTypeId}`, body);
      return data;
    },
    async deactivateLeaveType(leaveTypeId: number): Promise<void> {
      await http.delete(`/api/admin/leave-types/${leaveTypeId}`);
    },

    async listSystemParameters(params: { group?: string } = {}): Promise<SystemParameter[]> {
      const { data } = await http.get<SystemParameter[]>('/api/admin/system-parameters', { params });
      return data;
    },
    async createSystemParameter(body: SystemParameterRequest): Promise<SystemParameter> {
      const { data } = await http.post<SystemParameter>('/api/admin/system-parameters', body);
      return data;
    },
    async getSystemParameter(paramId: number): Promise<SystemParameter> {
      const { data } = await http.get<SystemParameter>(`/api/admin/system-parameters/${paramId}`);
      return data;
    },
    async updateSystemParameter(paramId: number, body: SystemParameterUpdateRequest): Promise<SystemParameter> {
      const { data } = await http.put<SystemParameter>(`/api/admin/system-parameters/${paramId}`, body);
      return data;
    },
    async deleteSystemParameter(paramId: number): Promise<void> {
      await http.delete(`/api/admin/system-parameters/${paramId}`);
    },

    async runLeaveAccrual(body: AccrualRunRequest = {}): Promise<BatchRunResult> {
      const { data } = await http.post<BatchRunResult>('/api/admin/leave/accrual', body);
      return data;
    },
    async runLeaveCarryover(body: CarryoverRunRequest): Promise<BatchRunResult> {
      const { data } = await http.post<BatchRunResult>('/api/admin/leave/carryover', body);
      return data;
    },
    async getLeaveJob(jobId: string): Promise<BatchRunResult> {
      const { data } = await http.get<BatchRunResult>(`/api/admin/leave/jobs/${jobId}`);
      return data;
    },

    async searchAuditLog(params: AuditLogSearchQuery = {}): Promise<AuditLogPage> {
      const { data } = await http.get<AuditLogPage>('/api/admin/audit-log', { params });
      return data;
    },
    auditLogCsv: (params: AuditLogSearchQuery = {}) => downloadCsv('/api/admin/audit-log', params, 'audit-log.csv'),
  },
  /** `integration-module` – `integration` tag. */
  integration: {
    async generateGlFeed(body: GlFeedRequest): Promise<IntegrationFile> {
      const { data } = await http.post<IntegrationFile>('/api/integration/gl-feed', body);
      return data;
    },
    async exportBenefitsFeed(body: BenefitsFeedRequest = {}): Promise<IntegrationFile> {
      const { data } = await http.post<IntegrationFile>('/api/integration/benefits-feed', body);
      return data;
    },
    async importTimeAttendance(file: File, hasHeader = true): Promise<TimeAttendanceImportResult> {
      const form = new FormData();
      form.append('file', file);
      form.append('hasHeader', String(hasHeader));
      const { data } = await http.post<TimeAttendanceImportResult>('/api/integration/time-attendance/import', form);
      return data;
    },
    async listFiles(params: IntegrationFileListQuery = {}): Promise<IntegrationFilePage> {
      const { data } = await http.get<IntegrationFilePage>('/api/integration/files', { params });
      return data;
    },
    async getFile(fileId: string): Promise<IntegrationFile> {
      const { data } = await http.get<IntegrationFile>(`/api/integration/files/${fileId}`);
      return data;
    },
    async downloadFile(fileId: string): Promise<string> {
      const { data } = await http.get<string>(`/api/integration/files/${fileId}/content`, { responseType: 'text', transformResponse: (d: string) => d });
      return data;
    },
    async status(): Promise<IntegrationStatus[]> {
      const { data } = await http.get<IntegrationStatus[]>('/api/integration/status');
      return data;
    },
  },
};

export type Api = typeof api;
