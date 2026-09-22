import type {
  CycleStatus,
  GoalCategory,
  GoalStatus,
  PerformanceGoal,
  PerformanceReview,
  RatingLabel,
  ReviewCycle,
  ReviewStatus,
  TeamReviewRow,
} from '../api/types';

export interface MockEmployee {
  empId: number;
  name: string;
  jobTitle: string;
  deptId: number;
  deptName: string;
  managerEmpId: number | null;
  active: boolean;
}

const now = '2025-01-15T00:00:00Z';
const employees: MockEmployee[] = [
  { empId: 1, name: 'JAMES RICHARDSON', jobTitle: 'CEO', deptId: 1, deptName: 'Executive', managerEmpId: null, active: true },
  { empId: 21, name: 'JENNIFER PARK', jobTitle: 'Manager', deptId: 3, deptName: 'Engineering', managerEmpId: 1, active: true },
  { empId: 11, name: 'DAVID MARTINEZ', jobTitle: 'Analyst', deptId: 3, deptName: 'Engineering', managerEmpId: 21, active: true },
  { empId: 12, name: 'EMILY JOHNSON', jobTitle: 'Analyst', deptId: 2, deptName: 'Finance', managerEmpId: 21, active: true },
  { empId: 13, name: 'SARAH LEE', jobTitle: 'Analyst', deptId: 3, deptName: 'Engineering', managerEmpId: 21, active: true },
];

let cycles: ReviewCycle[] = [];
let reviews: PerformanceReview[] = [];
let goals: PerformanceGoal[] = [];
let nextCycleId = 4;
let nextReviewId = 105;
let nextGoalId = 203;

export function ratingLabelForMock(rating: number): RatingLabel {
  if (rating >= 4.5) return 'Exceptional';
  if (rating >= 3.5) return 'Exceeds Expectations';
  if (rating >= 2.5) return 'Meets Expectations';
  if (rating >= 1.5) return 'Needs Improvement';
  return 'Unsatisfactory';
}

function employeeName(empId: number) {
  return employees.find((e) => e.empId === empId)?.name ?? `Employee #${empId}`;
}

function review(
  reviewId: number,
  cycleId: number,
  empId: number,
  reviewerEmpId: number,
  status: ReviewStatus,
  extra: Partial<PerformanceReview> = {},
): PerformanceReview {
  return {
    reviewId,
    cycleId,
    empId,
    employeeName: employeeName(empId),
    reviewerEmpId,
    reviewerName: employeeName(reviewerEmpId),
    reviewType: 'ANNUAL',
    status,
    overallRating: null,
    ratingLabel: null,
    selfAssessment: null,
    managerAssessment: null,
    strengths: null,
    areasForImprovement: null,
    developmentPlan: null,
    employeeComments: null,
    employeeAckDate: null,
    calibratedRating: null,
    calibrationNotes: null,
    createdBy: 'system',
    createdDate: now,
    modifiedBy: null,
    modifiedDate: null,
    ...extra,
  };
}

function goal(goalId: number, reviewId: number, title: string, category: GoalCategory, weightPct: number, progressPct: number, status: GoalStatus): PerformanceGoal {
  return {
    goalId,
    reviewId,
    empId: reviews.find((r) => r.reviewId === reviewId)?.empId ?? 0,
    goalTitle: title,
    goalDescription: null,
    goalCategory: category,
    weightPct,
    targetDate: null,
    status,
    progressPct,
    selfRating: null,
    managerRating: null,
    comments: null,
    createdBy: 'system',
    createdDate: now,
    modifiedBy: null,
    modifiedDate: null,
  };
}

export function resetPerformanceState() {
  cycles = [
    { cycleId: 1, cycleName: 'FY2024 Annual Review', cycleYear: 2024, startDate: '2024-01-01', endDate: '2024-12-31', selfReviewDue: '2024-11-15', managerReviewDue: '2024-12-15', calibrationDue: null, status: 'CLOSED', createdBy: 'system', createdDate: now, modifiedBy: null, modifiedDate: null },
    { cycleId: 2, cycleName: 'FY2025 Annual Review', cycleYear: 2025, startDate: '2025-01-01', endDate: '2025-12-31', selfReviewDue: '2025-11-15', managerReviewDue: '2025-12-15', calibrationDue: null, status: 'OPEN', createdBy: 'system', createdDate: now, modifiedBy: null, modifiedDate: null },
    { cycleId: 3, cycleName: 'FY2026 Annual Review', cycleYear: 2026, startDate: '2026-01-01', endDate: '2026-12-31', selfReviewDue: null, managerReviewDue: null, calibrationDue: null, status: 'DRAFT', createdBy: 'system', createdDate: now, modifiedBy: null, modifiedDate: null },
  ];
  reviews = [
    review(101, 2, 11, 21, 'NOT_STARTED'),
    review(102, 2, 12, 21, 'MANAGER_REVIEW', { selfAssessment: 'I improved our reporting workflow this year.' }),
    review(103, 2, 21, 1, 'COMPLETED', { overallRating: 4.2, ratingLabel: 'Exceeds Expectations', managerAssessment: 'Strong leadership and delivery.' }),
    review(100, 1, 11, 21, 'ACKNOWLEDGED', { overallRating: 3.4, ratingLabel: 'Meets Expectations', employeeAckDate: '2024-12-20' }),
    review(104, 1, 12, 21, 'ACKNOWLEDGED', { overallRating: 4.7, ratingLabel: 'Exceptional', employeeAckDate: '2024-12-20' }),
  ];
  goals = [
    goal(201, 101, 'Ship Phase 1', 'BUSINESS', 50, 0, 'NOT_STARTED'),
    goal(202, 101, 'Complete PostgreSQL training', 'DEVELOPMENT', 25, 40, 'IN_PROGRESS'),
  ];
  nextCycleId = 4;
  nextReviewId = 105;
  nextGoalId = 203;
}

resetPerformanceState();

export function getMockEmployee(empId: number) {
  return employees.find((e) => e.empId === empId);
}

export function getMockCycle(cycleId: number) {
  return cycles.find((c) => c.cycleId === cycleId);
}

export function getMockReview(reviewId: number) {
  return reviews.find((r) => r.reviewId === reviewId);
}

export function getMockGoal(goalId: number) {
  return goals.find((g) => g.goalId === goalId);
}

export function listMockCycles() {
  return cycles;
}

export function listMockReviews() {
  return reviews;
}

export function listMockGoals() {
  return goals;
}

export function createMockCycle(body: Omit<ReviewCycle, 'cycleId' | 'status' | 'createdBy' | 'createdDate' | 'modifiedBy' | 'modifiedDate'>, createdBy: string) {
  const value: ReviewCycle = { ...body, cycleId: nextCycleId++, status: 'DRAFT', createdBy, createdDate: now, modifiedBy: null, modifiedDate: null };
  cycles.push(value);
  return value;
}

export function updateMockCycle(cycle: ReviewCycle, body: Omit<ReviewCycle, 'cycleId' | 'status' | 'createdBy' | 'createdDate' | 'modifiedBy' | 'modifiedDate'>, modifiedBy: string) {
  Object.assign(cycle, body, { modifiedBy, modifiedDate: now });
  return cycle;
}

export function setCycleStatus(cycle: ReviewCycle, status: CycleStatus, modifiedBy: string) {
  Object.assign(cycle, { status, modifiedBy, modifiedDate: now });
  return cycle;
}

export function createMockReview(cycleId: number, empId: number, reviewerEmpId: number, createdBy: string) {
  const value = review(nextReviewId++, cycleId, empId, reviewerEmpId, 'NOT_STARTED', { createdBy });
  reviews.push(value);
  return value;
}

export function updateMockReview(reviewRow: PerformanceReview, patch: Partial<PerformanceReview>, modifiedBy: string) {
  Object.assign(reviewRow, patch, { modifiedBy, modifiedDate: now });
  return reviewRow;
}

export function createMockGoal(reviewRow: PerformanceReview, body: { goalTitle: string; goalDescription?: string | null; goalCategory?: GoalCategory; weightPct?: number; targetDate?: string | null }, createdBy: string) {
  const value: PerformanceGoal = {
    ...goal(nextGoalId++, reviewRow.reviewId, body.goalTitle, body.goalCategory ?? 'BUSINESS', body.weightPct ?? 0, 0, 'NOT_STARTED'),
    goalDescription: body.goalDescription ?? null,
    targetDate: body.targetDate ?? null,
    createdBy,
  };
  goals.push(value);
  return value;
}

export function updateMockGoal(goalRow: PerformanceGoal, patch: Partial<PerformanceGoal>, modifiedBy: string) {
  Object.assign(goalRow, patch, { modifiedBy, modifiedDate: now });
  return goalRow;
}

export function teamReviewRows(cycleId: number, reviewerEmpId: number): TeamReviewRow[] {
  return reviews
    .filter((r) => r.cycleId === cycleId && r.reviewerEmpId === reviewerEmpId)
    .sort((a, b) => a.employeeName.localeCompare(b.employeeName) || a.reviewId - b.reviewId)
    .map((r) => {
      const employee = getMockEmployee(r.empId)!;
      return { reviewId: r.reviewId, empId: r.empId, employeeName: r.employeeName, jobTitle: employee.jobTitle, deptName: employee.deptName, status: r.status, overallRating: r.overallRating, ratingLabel: r.ratingLabel };
    });
}

export function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}
