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
} from './types';

/**
 * One function per operationId in contracts/p0-foundation/openapi.yaml and
 * contracts/p1-performance/openapi.yaml.
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
};

export type Api = typeof api;
