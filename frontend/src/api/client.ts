import { http, setAccessToken } from './http';
import type {
  ChangePasswordRequest,
  Dependent,
  DependentRequest,
  EmergencyContact,
  EmergencyContactRequest,
  EmployeeCreateRequest,
  EmployeeDetail,
  EmployeeHistoryEntry,
  EmployeeListQuery,
  EmployeeTerminateRequest,
  EmployeeTransferRequest,
  EmployeeUpdateRequest,
  PageOfEmployeeListItem,
  SalaryChangeRequest,
  SalaryRecord,
  CurrentUser,
  DepartmentRef,
  EmployeeSearchQuery,
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
  LeaveRequestsForEmployeeQuery,
  PageOfLeaveRequest,
  PendingLeaveApproval,
  TeamCalendarEntry,
} from './types';

/**
 * One function per operationId in contracts/p0-foundation/openapi.yaml,
 * contracts/p1-performance/openapi.yaml and contracts/p2-leave/openapi.yaml (the
 * `x-deferred` P5 admin routes are not mounted and have no client).
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
    // contracts/p3-employee/openapi.yaml – employee-service (+ salary-module for /salary)
    async listEmployees(params: EmployeeListQuery = {}): Promise<PageOfEmployeeListItem> {
      const { data } = await http.get<PageOfEmployeeListItem>('/api/employees', { params });
      return data;
    },
    async createEmployee(body: EmployeeCreateRequest): Promise<EmployeeDetail> {
      const { data } = await http.post<EmployeeDetail>('/api/employees', body);
      return data;
    },
    async getEmployee(id: number): Promise<EmployeeDetail> {
      const { data } = await http.get<EmployeeDetail>(`/api/employees/${id}`);
      return data;
    },
    /** `If-Match` carries the `version` echoed by GET (optimistic locking, 409 CONFLICT). */
    async updateEmployee(id: number, version: number, body: EmployeeUpdateRequest): Promise<EmployeeDetail> {
      const { data } = await http.put<EmployeeDetail>(`/api/employees/${id}`, body, { headers: { 'If-Match': `"${version}"` } });
      return data;
    },
    async terminateEmployee(id: number, body: EmployeeTerminateRequest): Promise<EmployeeDetail> {
      const { data } = await http.post<EmployeeDetail>(`/api/employees/${id}/terminate`, body);
      return data;
    },
    async transferEmployee(id: number, body: EmployeeTransferRequest): Promise<EmployeeDetail> {
      const { data } = await http.post<EmployeeDetail>(`/api/employees/${id}/transfer`, body);
      return data;
    },
    async getCurrentSalary(id: number): Promise<SalaryRecord> {
      const { data } = await http.get<SalaryRecord>(`/api/employees/${id}/salary`);
      return data;
    },
    async changeSalary(id: number, body: SalaryChangeRequest): Promise<SalaryRecord> {
      const { data } = await http.post<SalaryRecord>(`/api/employees/${id}/salary`, body);
      return data;
    },
    async listSalaryHistory(id: number): Promise<SalaryRecord[]> {
      const { data } = await http.get<SalaryRecord[]>(`/api/employees/${id}/salary/history`);
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
};

export type Api = typeof api;
