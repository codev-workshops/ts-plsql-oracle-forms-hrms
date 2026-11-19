import { api } from '../../api/client';

export interface ReviewCycle {
  cycleId: number;
  cycleName: string;
  cycleYear?: number;
  startDate: string;
  endDate: string;
  selfReviewDeadline?: string | null;
  managerReviewDeadline?: string | null;
  status: string;
}

export interface NewCycle {
  cycleName: string;
  cycleYear?: number;
  startDate: string;
  endDate: string;
  selfReviewDeadline?: string;
  managerReviewDeadline?: string;
}

export interface PerformanceGoal {
  goalId: number;
  goalTitle: string;
  goalDescription?: string | null;
  weightPct?: number | null;
  targetDate?: string | null;
  progressPct?: number | null;
  status: string;
}

export interface NewGoal {
  goalTitle: string;
  goalDescription?: string;
  weightPct?: number;
  targetDate?: string;
}

export interface PerformanceReview {
  reviewId: number;
  cycleId: number;
  empId: number;
  employeeName: string;
  reviewerEmpId: number;
  reviewerName: string;
  status: string;
  selfAssessment?: string | null;
  selfRating?: number | null;
  managerComments?: string | null;
  overallRating?: number | null;
  ratingLabel?: string | null;
  goals?: PerformanceGoal[];
}

export interface ManagerReview {
  overallRating: number;
  managerComments?: string;
  strengths?: string;
  improvements?: string;
}

export interface RatingCount {
  ratingLabel: string;
  count: number;
}

export interface BatchResult {
  operation: string;
  rowsAffected: number;
}

export const performanceApi = {
  cycles: () => api<ReviewCycle[]>('/performance/cycles'),
  createCycle: (body: NewCycle) => api<ReviewCycle>('/performance/cycles', { method: 'POST', body }),
  openCycle: (id: number) => api<ReviewCycle>(`/performance/cycles/${id}/open`, { method: 'POST' }),
  closeCycle: (id: number) => api<ReviewCycle>(`/performance/cycles/${id}/close`, { method: 'POST' }),
  generateReviews: (id: number) =>
    api<BatchResult>(`/performance/cycles/${id}/generate-reviews`, { method: 'POST' }),
  ratingDistribution: (id: number) => api<RatingCount[]>(`/performance/cycles/${id}/rating-distribution`),
  reviews: (scope: 'mine' | 'team') => api<PerformanceReview[]>(`/performance/reviews?scope=${scope}`),
  review: (id: number) => api<PerformanceReview>(`/performance/reviews/${id}`),
  selfAssessment: (id: number, selfAssessment: string, selfRating?: number) =>
    api<PerformanceReview>(`/performance/reviews/${id}/self-assessment`, {
      method: 'POST',
      body: { selfAssessment, selfRating },
    }),
  managerReview: (id: number, body: ManagerReview) =>
    api<PerformanceReview>(`/performance/reviews/${id}/manager-review`, { method: 'POST', body }),
  acknowledge: (id: number) => api<PerformanceReview>(`/performance/reviews/${id}/acknowledge`, { method: 'POST' }),
  addGoal: (reviewId: number, body: NewGoal) =>
    api<PerformanceGoal>(`/performance/reviews/${reviewId}/goals`, { method: 'POST', body }),
  updateProgress: (goalId: number, progressPct: number, status?: string) =>
    api<PerformanceGoal>(`/performance/goals/${goalId}/progress`, { method: 'POST', body: { progressPct, status } }),
};
