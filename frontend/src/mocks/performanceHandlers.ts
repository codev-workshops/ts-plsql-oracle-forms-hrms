import { HttpResponse, http } from 'msw';
import type { ApiError, Authority, GoalProgressRequest, GoalRequest, ManagerReviewRequest, ReviewCycleRequest, SelfAssessmentRequest } from '../api/types';
import { getDto } from '../validation/schema';
import {
  clone,
  createMockCycle,
  createMockGoal,
  createMockReview,
  getMockCycle,
  getMockEmployee,
  getMockGoal,
  getMockReview,
  listMockCycles,
  listMockGoals,
  listMockReviews,
  ratingLabelForMock,
  resetPerformanceState,
  setCycleStatus,
  teamReviewRows,
  updateMockCycle,
  updateMockGoal,
  updateMockReview,
} from './performanceStore';

interface SessionUser {
  userId: string;
  empId: number;
  roles: Authority[];
}

type Authenticate = (request: Request) => SessionUser | null;

const TRACE = '3f1c2d9e8b7a4c10';
const statuses = ['DRAFT', 'OPEN', 'IN_PROGRESS', 'CALIBRATION', 'CLOSED'] as const;
const reviewStatuses = ['NOT_STARTED', 'SELF_REVIEW', 'MANAGER_REVIEW', 'MEETING_SCHEDULED', 'COMPLETED', 'ACKNOWLEDGED'] as const;
const sortValues = ['cycleYear,desc', 'cycleYear,asc', 'startDate,desc', 'startDate,asc'] as const;

function error(status: number, body: Omit<ApiError, 'traceId'>, headers?: Record<string, string>) {
  return HttpResponse.json<ApiError>({ ...body, traceId: TRACE }, { status, headers });
}

const unauthorized = () => error(401, { code: 'TOKEN_INVALID', message: 'Session has expired' });
const forbidden = () => error(403, { code: 'FORBIDDEN', message: 'You do not have permission to perform this action' });
const validation = (field: string, message = 'Request validation failed') => error(400, { code: 'VALIDATION_FAILED', message, field });
const missing = (code: 'CYCLE_NOT_FOUND' | 'REVIEW_NOT_FOUND' | 'GOAL_NOT_FOUND', message: string) => error(404, { code, message });
const statusError = (message: string) => error(422, { code: '-20402', message });

async function body<T>(request: Request): Promise<T> {
  try {
    return (await request.json()) as T;
  } catch {
    return {} as T;
  }
}

function has(user: SessionUser, authority: Authority) {
  return user.roles.includes(authority);
}

function hasOne(user: SessionUser, ...authorities: Authority[]) {
  return authorities.some((a) => has(user, a));
}

type PathValue = string | readonly string[] | undefined;

function pathValue(params: Record<string, PathValue>, key: string) {
  const value = params[key];
  return Array.isArray(value) ? String(value[0]) : value ?? '';
}

function cycleId(params: Record<string, PathValue>) {
  return Number(pathValue(params, 'cycleId'));
}

function reviewId(params: Record<string, PathValue>) {
  return Number(pathValue(params, 'reviewId'));
}

function goalId(params: Record<string, PathValue>) {
  return Number(pathValue(params, 'goalId'));
}

function validDate(value: unknown) {
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value);
}

function validCycleBody(value: Partial<ReviewCycleRequest>) {
  if (!value.cycleName?.trim()) return 'cycleName';
  const year = value.cycleYear;
  const startDate = value.startDate;
  const endDate = value.endDate;
  if (typeof year !== 'number' || !Number.isInteger(year) || year < 2000 || year > 2099) return 'cycleYear';
  if (typeof startDate !== 'string' || !validDate(startDate)) return 'startDate';
  if (typeof endDate !== 'string' || !validDate(endDate)) return 'endDate';
  if (endDate < startDate) return 'endDate';
  for (const field of ['selfReviewDue', 'managerReviewDue', 'calibrationDue'] as const) {
    if (value[field] !== null && value[field] !== undefined && !validDate(value[field])) return field;
  }
  return null;
}

function validReviewBody(value: Partial<SelfAssessmentRequest & ManagerReviewRequest>, manager = false) {
  if (manager && (typeof value.overallRating !== 'number' || !Number.isFinite(value.overallRating))) return 'overallRating';
  if (manager && !value.managerAssessment?.trim()) return 'managerAssessment';
  if (!manager && !value.selfAssessment?.trim()) return 'selfAssessment';
  return null;
}

function validGoalBody(value: Partial<GoalRequest>) {
  if (!value.goalTitle?.trim()) return 'goalTitle';
  if (value.goalCategory && !getDto('GoalRequest').fields.goalCategory.values?.includes(value.goalCategory)) return 'goalCategory';
  if (value.weightPct !== undefined && (typeof value.weightPct !== 'number' || value.weightPct < 0 || value.weightPct > 100)) return 'weightPct';
  if (value.targetDate !== undefined && value.targetDate !== null && !validDate(value.targetDate)) return 'targetDate';
  return null;
}

function validProgressBody(value: Partial<GoalProgressRequest>) {
  if (typeof value.progressPct !== 'number' || value.progressPct < 0 || value.progressPct > 100) return 'progressPct';
  if (value.status !== undefined && value.status !== null && !getDto('GoalProgressRequest').fields.status.values?.includes(value.status)) return 'status';
  return null;
}

function scopedReview(user: SessionUser, row: ReturnType<typeof getMockReview>, allowView = false) {
  return row && (row.empId === user.empId || row.reviewerEmpId === user.empId || (allowView && hasOne(user, 'PERFORMANCE:VIEW', 'PERFORMANCE:ADMIN')));
}

export function createPerformanceHandlers(authenticate: Authenticate) {
  const requireAuth = (request: Request) => authenticate(request);

  return [
    http.get('/api/performance/cycles', ({ request }) => {
      const user = requireAuth(request);
      if (!user) return unauthorized();
      const url = new URL(request.url);
      const rawStatus = url.searchParams.get('status') ?? 'OPEN,DRAFT';
      const sort = url.searchParams.get('sort') ?? 'cycleYear,desc';
      const selected = rawStatus.split(',');
      if (!selected.every((s) => statuses.includes(s as (typeof statuses)[number]))) return validation('status');
      if (!sortValues.includes(sort as (typeof sortValues)[number])) return validation('sort');
      const rows = listMockCycles().filter((c) => selected.includes(c.status)).sort((a, b) => {
        const [property, direction] = sort.split(',');
        const result = property === 'cycleYear' ? a.cycleYear - b.cycleYear : a.startDate.localeCompare(b.startDate);
        return direction === 'desc' ? -result : result;
      });
      return HttpResponse.json(clone(rows));
    }),

    http.post('/api/performance/cycles', async ({ request }) => {
      const user = requireAuth(request);
      if (!user) return unauthorized();
      if (!has(user, 'PERFORMANCE:ADMIN')) return forbidden();
      const value = await body<ReviewCycleRequest>(request);
      const field = validCycleBody(value);
      if (field) return validation(field);
      const row = createMockCycle(value as never, user.userId);
      return HttpResponse.json(clone(row), { status: 201, headers: { Location: `/api/performance/cycles/${row.cycleId}` } });
    }),

    http.get('/api/performance/cycles/:cycleId', ({ request, params }) => {
      const user = requireAuth(request);
      if (!user) return unauthorized();
      const row = getMockCycle(cycleId(params));
      return row ? HttpResponse.json(clone(row)) : missing('CYCLE_NOT_FOUND', 'Review cycle not found');
    }),

    http.put('/api/performance/cycles/:cycleId', async ({ request, params }) => {
      const user = requireAuth(request);
      if (!user) return unauthorized();
      if (!has(user, 'PERFORMANCE:ADMIN')) return forbidden();
      const row = getMockCycle(cycleId(params));
      if (!row) return missing('CYCLE_NOT_FOUND', 'Review cycle not found');
      const value = await body<ReviewCycleRequest>(request);
      const field = validCycleBody(value);
      if (field) return validation(field);
      if (row.status !== 'DRAFT') return error(422, { code: '-20401', message: 'Cannot edit cycle - must be in DRAFT status' });
      return HttpResponse.json(clone(updateMockCycle(row, value as never, user.userId)));
    }),

    http.post('/api/performance/cycles/:cycleId/open', ({ request, params }) => {
      const user = requireAuth(request);
      if (!user) return unauthorized();
      if (!has(user, 'PERFORMANCE:ADMIN')) return forbidden();
      const row = getMockCycle(cycleId(params));
      if (!row) return missing('CYCLE_NOT_FOUND', 'Review cycle not found');
      if (row.status !== 'DRAFT') return error(422, { code: '-20401', message: 'Cannot open cycle - must be in DRAFT status' });
      return HttpResponse.json(clone(setCycleStatus(row, 'OPEN', user.userId)));
    }),

    http.post('/api/performance/cycles/:cycleId/close', ({ request, params }) => {
      const user = requireAuth(request);
      if (!user) return unauthorized();
      if (!has(user, 'PERFORMANCE:ADMIN')) return forbidden();
      const row = getMockCycle(cycleId(params));
      if (!row) return missing('CYCLE_NOT_FOUND', 'Review cycle not found');
      if (!['OPEN', 'IN_PROGRESS', 'CALIBRATION'].includes(row.status)) return error(422, { code: '-20401', message: 'Cannot close cycle - must be OPEN, IN_PROGRESS or CALIBRATION' });
      return HttpResponse.json(clone(setCycleStatus(row, 'CLOSED', user.userId)));
    }),

    http.post('/api/performance/cycles/:cycleId/generate-reviews', ({ request, params }) => {
      const user = requireAuth(request);
      if (!user) return unauthorized();
      if (!has(user, 'PERFORMANCE:ADMIN')) return forbidden();
      const row = getMockCycle(cycleId(params));
      if (!row) return missing('CYCLE_NOT_FOUND', 'Review cycle not found');
      if (!['DRAFT', 'OPEN'].includes(row.status)) return error(422, { code: '-20401', message: 'Cannot generate reviews - cycle must be DRAFT or OPEN' });
      let generated = 0;
      let skipped = 0;
      for (const employee of [1, 21, 11, 12, 13].map(getMockEmployee).filter((e) => e?.active && e.managerEmpId)) {
        if (listMockReviews().some((r) => r.cycleId === row.cycleId && r.empId === employee!.empId)) skipped += 1;
        else {
          createMockReview(row.cycleId, employee!.empId, employee!.managerEmpId!, user.userId);
          generated += 1;
        }
      }
      return HttpResponse.json({ cycleId: row.cycleId, generated, skipped });
    }),

    http.get('/api/performance/cycles/:cycleId/reviews', ({ request, params }) => {
      const user = requireAuth(request);
      if (!user) return unauthorized();
      if (!hasOne(user, 'PERFORMANCE:VIEW', 'PERFORMANCE:ADMIN')) return forbidden();
      const row = getMockCycle(cycleId(params));
      if (!row) return missing('CYCLE_NOT_FOUND', 'Review cycle not found');
      const url = new URL(request.url);
      const page = Number(url.searchParams.get('page') ?? 0);
      const size = Number(url.searchParams.get('size') ?? 20);
      const rawStatus = url.searchParams.get('status');
      if (page < 0 || size < 1 || size > 100) return validation(page < 0 ? 'page' : 'size');
      const filter = rawStatus ? rawStatus.split(',') : [];
      if (filter.length && !filter.every((s) => reviewStatuses.includes(s as (typeof reviewStatuses)[number]))) return validation('status');
      const rows = listMockReviews().filter((r) => r.cycleId === row.cycleId && (!filter.length || filter.includes(r.status))).sort((a, b) => a.employeeName.localeCompare(b.employeeName) || a.reviewId - b.reviewId);
      const content = rows.slice(page * size, page * size + size);
      return HttpResponse.json({ content: clone(content), page, size, totalElements: rows.length, totalPages: Math.ceil(rows.length / size) });
    }),

    http.get('/api/performance/reviews/mine', ({ request }) => {
      const user = requireAuth(request);
      if (!user) return unauthorized();
      const cycle = new URL(request.url).searchParams.get('cycleId');
      const rows = listMockReviews().filter((r) => r.empId === user.empId && (!cycle || r.cycleId === Number(cycle))).sort((a, b) => b.cycleId - a.cycleId || b.reviewId - a.reviewId);
      return HttpResponse.json(clone(rows));
    }),

    http.get('/api/performance/reviews/:reviewId', ({ request, params }) => {
      const user = requireAuth(request);
      if (!user) return unauthorized();
      const row = getMockReview(reviewId(params));
      if (!row) return missing('REVIEW_NOT_FOUND', 'Performance review not found');
      if (!scopedReview(user, row, true)) return forbidden();
      return HttpResponse.json(clone(row));
    }),

    http.post('/api/performance/reviews/:reviewId/self-assessment', async ({ request, params }) => {
      const user = requireAuth(request);
      if (!user) return unauthorized();
      const row = getMockReview(reviewId(params));
      if (!row) return missing('REVIEW_NOT_FOUND', 'Performance review not found');
      if (row.empId !== user.empId) return forbidden();
      const value = await body<SelfAssessmentRequest>(request);
      const field = validReviewBody(value);
      if (field) return validation(field);
      if (!['NOT_STARTED', 'SELF_REVIEW'].includes(row.status)) return statusError('Review not found or not in correct status');
      return HttpResponse.json(clone(updateMockReview(row, { selfAssessment: value.selfAssessment, status: 'MANAGER_REVIEW' }, user.userId)));
    }),

    http.post('/api/performance/reviews/:reviewId/manager-review', async ({ request, params }) => {
      const user = requireAuth(request);
      if (!user) return unauthorized();
      const row = getMockReview(reviewId(params));
      if (!row) return missing('REVIEW_NOT_FOUND', 'Performance review not found');
      if (row.reviewerEmpId !== user.empId) return forbidden();
      const value = await body<ManagerReviewRequest>(request);
      const field = validReviewBody(value, true);
      if (field === 'overallRating' && typeof value.overallRating === 'number' && (value.overallRating < 1 || value.overallRating > 5)) {
        return error(400, { code: '-20403', message: 'Rating must be between 1.0 and 5.0', field: 'overallRating' });
      }
      if (field) return validation(field);
      if (value.overallRating < 1 || value.overallRating > 5) return error(400, { code: '-20403', message: 'Rating must be between 1.0 and 5.0', field: 'overallRating' });
      if (!['MANAGER_REVIEW', 'MEETING_SCHEDULED'].includes(row.status)) return statusError('Review not found or not in correct status');
      return HttpResponse.json(clone(updateMockReview(row, {
        status: 'COMPLETED',
        overallRating: value.overallRating,
        ratingLabel: ratingLabelForMock(value.overallRating),
        managerAssessment: value.managerAssessment,
        strengths: value.strengths ?? null,
        areasForImprovement: value.improvementAreas ?? null,
        developmentPlan: value.developmentPlan ?? null,
      }, user.userId)));
    }),

    http.post('/api/performance/reviews/:reviewId/acknowledge', async ({ request, params }) => {
      const user = requireAuth(request);
      if (!user) return unauthorized();
      const row = getMockReview(reviewId(params));
      if (!row) return missing('REVIEW_NOT_FOUND', 'Performance review not found');
      if (row.empId !== user.empId) return forbidden();
      const value = await body<{ employeeComments?: string | null }>(request);
      if (!['COMPLETED'].includes(row.status)) return statusError('Review not found or not in correct status');
      return HttpResponse.json(clone(updateMockReview(row, { status: 'ACKNOWLEDGED', employeeComments: value.employeeComments ?? null, employeeAckDate: '2025-01-15' }, user.userId)));
    }),

    http.get('/api/performance/reviews/:reviewId/goals', ({ request, params }) => {
      const user = requireAuth(request);
      if (!user) return unauthorized();
      const row = getMockReview(reviewId(params));
      if (!row) return missing('REVIEW_NOT_FOUND', 'Performance review not found');
      if (!scopedReview(user, row, true)) return forbidden();
      return HttpResponse.json(clone(listMockGoals().filter((g) => g.reviewId === row.reviewId).sort((a, b) => a.goalId - b.goalId)));
    }),

    http.post('/api/performance/reviews/:reviewId/goals', async ({ request, params }) => {
      const user = requireAuth(request);
      if (!user) return unauthorized();
      const row = getMockReview(reviewId(params));
      if (!row) return missing('REVIEW_NOT_FOUND', 'Performance review not found');
      if (!scopedReview(user, row)) return forbidden();
      const value = await body<GoalRequest>(request);
      const field = validGoalBody(value);
      if (field) return validation(field);
      if (['COMPLETED', 'ACKNOWLEDGED'].includes(row.status)) return statusError('Review not found or not in correct status');
      const created = createMockGoal(row, value, user.userId);
      return HttpResponse.json(clone(created), { status: 201, headers: { Location: `/api/performance/goals/${created.goalId}` } });
    }),

    http.patch('/api/performance/goals/:goalId/progress', async ({ request, params }) => {
      const user = requireAuth(request);
      if (!user) return unauthorized();
      const row = getMockGoal(goalId(params));
      if (!row) return missing('GOAL_NOT_FOUND', 'Performance goal not found');
      const review = getMockReview(row.reviewId);
      if (!review || !scopedReview(user, review)) return forbidden();
      const value = await body<GoalProgressRequest>(request);
      const field = validProgressBody(value);
      if (field) return validation(field);
      if (review.status === 'ACKNOWLEDGED') return statusError('Review not found or not in correct status');
      const status = value.status ?? (value.progressPct >= 100 ? 'COMPLETED' : value.progressPct > 0 ? 'IN_PROGRESS' : row.status);
      return HttpResponse.json(clone(updateMockGoal(row, { progressPct: value.progressPct, status, ...(value.comments !== undefined && value.comments !== null ? { comments: value.comments } : {}) }, user.userId)));
    }),

    http.get('/api/performance/cycles/:cycleId/team-reviews', ({ request, params }) => {
      const user = requireAuth(request);
      if (!user) return unauthorized();
      const row = getMockCycle(cycleId(params));
      if (!row) return missing('CYCLE_NOT_FOUND', 'Review cycle not found');
      return HttpResponse.json(clone(teamReviewRows(row.cycleId, user.empId)));
    }),

    http.get('/api/performance/cycles/:cycleId/rating-distribution', ({ request, params }) => {
      const user = requireAuth(request);
      if (!user) return unauthorized();
      if (!hasOne(user, 'PERFORMANCE:VIEW', 'PERFORMANCE:ADMIN')) return forbidden();
      const row = getMockCycle(cycleId(params));
      if (!row) return missing('CYCLE_NOT_FOUND', 'Review cycle not found');
      const url = new URL(request.url);
      const deptValue = url.searchParams.get('deptId');
      const deptId = deptValue ? Number(deptValue) : undefined;
      if (deptValue && (deptId === undefined || !Number.isInteger(deptId) || deptId < 1)) return validation('deptId');
      const rated = listMockReviews().filter((r) => r.cycleId === row.cycleId && r.overallRating !== null && (!deptId || getMockEmployee(r.empId)?.deptId === deptId));
      const total = rated.length;
      const groups = new Map<string, { count: number; min: number }>();
      for (const r of rated) {
        const label = r.ratingLabel ?? ratingLabelForMock(r.overallRating!);
        const previous = groups.get(label);
        groups.set(label, { count: (previous?.count ?? 0) + 1, min: Math.min(previous?.min ?? r.overallRating!, r.overallRating!) });
      }
      return HttpResponse.json([...groups.entries()].sort((a, b) => b[1].min - a[1].min).map(([ratingLabel, value]) => ({
        ratingLabel,
        count: value.count,
        percentage: Math.round((value.count * 1000) / total) / 10,
      })));
    }),
  ];
}

export { resetPerformanceState };
