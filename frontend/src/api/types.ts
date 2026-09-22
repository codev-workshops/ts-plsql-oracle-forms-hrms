/**
 * Hand-written 1:1 TypeScript projection of contracts/p0-foundation/openapi.yaml
 * and contracts/p1-performance/openapi.yaml (components.schemas). Do not add fields
 * that are not in the contract.
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
  `${'PAYROLL' | 'EMPLOYEE' | 'LEAVE' | 'ADMIN' | 'REPORTS' | 'PERFORMANCE'}:${'VIEW' | 'EDIT' | 'APPROVE' | 'CREATE' | 'ADMIN' | 'VIEW_ALL'}`;

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

export type CycleStatus = 'DRAFT' | 'OPEN' | 'IN_PROGRESS' | 'CALIBRATION' | 'CLOSED';
export type ReviewStatus = 'NOT_STARTED' | 'SELF_REVIEW' | 'MANAGER_REVIEW' | 'MEETING_SCHEDULED' | 'COMPLETED' | 'ACKNOWLEDGED';
export type ReviewType = 'ANNUAL';
export type RatingLabel = 'Exceptional' | 'Exceeds Expectations' | 'Meets Expectations' | 'Needs Improvement' | 'Unsatisfactory';
export type GoalCategory = 'BUSINESS' | 'DEVELOPMENT' | 'LEADERSHIP' | 'INNOVATION' | 'COMPLIANCE';
export type GoalStatus = 'NOT_STARTED' | 'IN_PROGRESS' | 'COMPLETED' | 'DEFERRED' | 'CANCELLED';

export interface ReviewCycleRequest {
  cycleName: string;
  cycleYear: number;
  startDate: string;
  endDate: string;
  selfReviewDue?: string | null;
  managerReviewDue?: string | null;
  calibrationDue?: string | null;
}

export interface ReviewCycle {
  cycleId: number;
  cycleName: string;
  cycleYear: number;
  startDate: string;
  endDate: string;
  selfReviewDue?: string | null;
  managerReviewDue?: string | null;
  calibrationDue?: string | null;
  status: CycleStatus;
  createdBy: string;
  createdDate: string;
  modifiedBy?: string | null;
  modifiedDate?: string | null;
}

export interface GenerateReviewsResult {
  cycleId: number;
  generated: number;
  skipped: number;
}

export interface PerformanceReview {
  reviewId: number;
  cycleId: number;
  empId: number;
  employeeName: string;
  reviewerEmpId: number;
  reviewerName: string;
  reviewType: ReviewType;
  status: ReviewStatus;
  overallRating?: number | null;
  ratingLabel?: RatingLabel | null;
  selfAssessment?: string | null;
  managerAssessment?: string | null;
  strengths?: string | null;
  areasForImprovement?: string | null;
  developmentPlan?: string | null;
  employeeComments?: string | null;
  employeeAckDate?: string | null;
  calibratedRating?: number | null;
  calibrationNotes?: string | null;
  createdBy: string;
  createdDate: string;
  modifiedBy?: string | null;
  modifiedDate?: string | null;
}

export interface PageOfPerformanceReview {
  content: PerformanceReview[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface SelfAssessmentRequest {
  selfAssessment: string;
}

export interface ManagerReviewRequest {
  overallRating: number;
  managerAssessment: string;
  strengths?: string | null;
  improvementAreas?: string | null;
  developmentPlan?: string | null;
}

export interface AcknowledgeRequest {
  employeeComments?: string | null;
}

export interface GoalRequest {
  goalTitle: string;
  goalDescription?: string | null;
  goalCategory?: GoalCategory;
  weightPct?: number;
  targetDate?: string | null;
}

export interface GoalProgressRequest {
  progressPct: number;
  status?: GoalStatus | null;
  comments?: string | null;
}

export interface PerformanceGoal {
  goalId: number;
  reviewId: number;
  empId: number;
  goalTitle: string;
  goalDescription?: string | null;
  goalCategory: GoalCategory;
  weightPct: number;
  targetDate?: string | null;
  status: GoalStatus;
  progressPct: number;
  selfRating?: number | null;
  managerRating?: number | null;
  comments?: string | null;
  createdBy: string;
  createdDate: string;
  modifiedBy?: string | null;
  modifiedDate?: string | null;
}

export interface TeamReviewRow {
  reviewId: number;
  empId: number;
  employeeName: string;
  jobTitle: string;
  deptName: string;
  status: ReviewStatus;
  overallRating?: number | null;
  ratingLabel?: RatingLabel | null;
}

export interface RatingDistributionRow {
  ratingLabel: RatingLabel;
  count: number;
  percentage: number;
}

export interface ListCyclesQuery {
  status?: string;
  sort?: 'cycleYear,desc' | 'cycleYear,asc' | 'startDate,desc' | 'startDate,asc';
}

export interface ListCycleReviewsQuery {
  status?: string;
  page?: number;
  size?: number;
}

// ---------------------------------------------------------------------------
// contracts/p2-leave/openapi.yaml
// ---------------------------------------------------------------------------
export type LeaveRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED' | 'TAKEN';
export type HalfDayPeriod = 'AM' | 'PM';

export interface LeaveRequestCreateRequest {
  leaveTypeId: number;
  startDate: string;
  endDate: string;
  halfDay?: boolean;
  halfDayPeriod?: HalfDayPeriod | null;
  reason?: string | null;
}

export interface LeaveCancelRequest {
  reason?: string | null;
}

export interface LeaveApproveRequest {
  comments?: string | null;
}

export interface LeaveRejectRequest {
  comments: string;
}

export interface LeaveRequest {
  requestId: number;
  empId: number;
  empName: string;
  leaveTypeId: number;
  leaveTypeCode: string;
  leaveTypeName: string;
  startDate: string;
  endDate: string;
  totalDays: number;
  halfDay: boolean;
  halfDayPeriod: HalfDayPeriod | null;
  status: LeaveRequestStatus;
  reason: string | null;
  approverEmpId: number | null;
  approverName: string | null;
  approvalDate: string | null;
  approvalComments: string | null;
  createdDate: string;
  modifiedDate: string | null;
}

export interface PageOfLeaveRequest {
  content: LeaveRequest[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface LeaveBalance {
  balanceId: number;
  leaveTypeId: number;
  leaveTypeCode: string;
  leaveTypeName: string;
  calendarYear: number;
  openingBalance: number;
  accrued: number;
  used: number;
  adjustment: number;
  pending: number;
  carryoverFromPrev: number;
  available: number;
}

export interface BusinessDaysHoliday {
  holidayName: string;
  holidayDate: string;
  observedDate: string;
}

export interface BusinessDays {
  start: string;
  end: string;
  businessDays: number;
  holidays: BusinessDaysHoliday[];
}

export interface PendingLeaveApproval {
  requestId: number;
  empId: number;
  empNumber: string;
  empName: string;
  leaveTypeName: string;
  startDate: string;
  endDate: string;
  totalDays: number;
  halfDay: boolean;
  halfDayPeriod: HalfDayPeriod | null;
  reason: string | null;
  createdDate: string;
}

export interface TeamCalendarEntry {
  requestId: number;
  empId: number;
  empName: string;
  leaveTypeName: string;
  startDate: string;
  endDate: string;
  totalDays: number;
  halfDay: boolean;
  halfDayPeriod: HalfDayPeriod | null;
  status: 'APPROVED' | 'TAKEN';
}

export interface LeaveRequestFilter {
  /** Comma-separated subset of `LeaveRequestStatus`. */
  status?: string;
  year?: number;
}

export interface LeaveRequestsForEmployeeQuery extends LeaveRequestFilter {
  empId: number;
  page?: number;
  size?: number;
}

/* ------------------------------------------------------------------------------------------ */
/* contracts/p3-employee/openapi.yaml (components.schemas) – hand-written 1:1 projection.      */
/* ------------------------------------------------------------------------------------------ */

export type EmploymentStatus = 'ACTIVE' | 'ON_LEAVE' | 'SUSPENDED' | 'TERMINATED';
export type EmploymentType = 'FULL_TIME' | 'PART_TIME' | 'CONTRACT' | 'INTERN';
export type Gender = 'M' | 'F' | 'O';
export type Relationship = 'SPOUSE' | 'CHILD' | 'PARENT' | 'DOMESTIC_PARTNER' | 'OTHER';
export type HistoryChangeType =
  | 'HIRE'
  | 'TRANSFER'
  | 'PROMOTION'
  | 'DEMOTION'
  | 'SALARY_CHANGE'
  | 'TERMINATION'
  | 'REHIRE'
  | 'LEAVE_START'
  | 'LEAVE_END'
  | 'STATUS_CHANGE';
export type PayFrequency = 'WEEKLY' | 'BIWEEKLY' | 'SEMIMONTHLY' | 'MONTHLY';
export type SalaryBasis = 'ANNUAL' | 'HOURLY';

/** 2-dp decimal string on the wire (`NUMBER(12,2)`). */
export type Money = string;

export interface EmployeeCreateRequest {
  firstName: string;
  middleName?: string | null;
  lastName: string;
  dateOfBirth?: string | null;
  gender?: Gender | null;
  maritalStatus?: string | null;
  nationality?: string | null;
  ssn?: string | null;
  email?: string | null;
  phoneWork?: string | null;
  phoneMobile?: string | null;
  addressLine1?: string | null;
  addressLine2?: string | null;
  city?: string | null;
  stateProvince?: string | null;
  postalCode?: string | null;
  countryCode?: string | null;
  hireDate: string;
  deptId: number;
  jobId: number;
  managerEmpId?: number | null;
  locationCode?: string | null;
  employmentType?: EmploymentType | null;
  initialSalary?: Money | null;
  notes?: string | null;
}

export interface EmployeeUpdateRequest {
  firstName: string;
  middleName?: string | null;
  lastName: string;
  dateOfBirth?: string | null;
  gender?: Gender | null;
  maritalStatus?: string | null;
  nationality?: string | null;
  ssn?: string | null;
  email?: string | null;
  phoneWork?: string | null;
  phoneMobile?: string | null;
  addressLine1?: string | null;
  addressLine2?: string | null;
  city?: string | null;
  stateProvince?: string | null;
  postalCode?: string | null;
  countryCode?: string | null;
  jobId: number;
  managerEmpId?: number | null;
  employmentType?: EmploymentType | null;
  notes?: string | null;
}

export interface EmployeeTerminateRequest {
  effectiveDate: string;
  reason: string;
  comments?: string | null;
}

export interface EmployeeTransferRequest {
  effectiveDate: string;
  deptId: number;
  newJobId?: number | null;
  newManagerEmpId?: number | null;
  newLocationCode?: string | null;
  reasonCode?: string | null;
  comments?: string | null;
}

export interface SalaryChangeRequest {
  effectiveDate: string;
  baseSalary: Money;
  currencyCode?: string | null;
  payFrequency?: PayFrequency | null;
  salaryBasis?: SalaryBasis | null;
  changeReason: string;
}

export interface DependentRequest {
  firstName: string;
  lastName: string;
  relationship: Relationship;
  dateOfBirth?: string | null;
  ssn?: string | null;
  benefitsEnrolled?: boolean | null;
  active?: boolean | null;
}

export interface EmergencyContactRequest {
  contactName: string;
  relationship?: string | null;
  phonePrimary: string;
  phoneSecondary?: string | null;
  email?: string | null;
  priorityOrder?: number | null;
  active?: boolean | null;
}

export interface EmployeeDetail {
  id: number;
  empNumber: string;
  firstName: string;
  middleName?: string | null;
  lastName: string;
  dateOfBirth?: string | null;
  gender?: Gender | null;
  maritalStatus?: string | null;
  nationality?: string | null;
  ssnLast4?: string | null;
  email?: string | null;
  phoneWork?: string | null;
  phoneMobile?: string | null;
  addressLine1?: string | null;
  addressLine2?: string | null;
  city?: string | null;
  stateProvince?: string | null;
  postalCode?: string | null;
  countryCode?: string | null;
  hireDate: string;
  terminationDate?: string | null;
  terminationReason?: string | null;
  deptId: number;
  deptName: string;
  jobId: number;
  jobTitle: string;
  gradeCode?: string | null;
  managerEmpId?: number | null;
  managerName?: string | null;
  locationCode?: string | null;
  locationName?: string | null;
  employmentType: EmploymentType;
  employmentStatus: EmploymentStatus;
  active: boolean;
  notes?: string | null;
  version: number;
  createdBy: string;
  createdDate: string;
  modifiedBy?: string | null;
  modifiedDate?: string | null;
}

export interface EmployeeListItem {
  id: number;
  empNumber: string;
  firstName: string;
  lastName: string;
  email?: string | null;
  deptId: number;
  deptName: string;
  jobId: number;
  jobTitle: string;
  managerEmpId?: number | null;
  managerName?: string | null;
  locationCode?: string | null;
  hireDate: string;
  employmentType?: EmploymentType;
  employmentStatus: EmploymentStatus;
  active: boolean;
}

export interface PageOfEmployeeListItem {
  content: EmployeeListItem[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface SalaryRecord {
  salaryId: number;
  empId: number;
  effectiveDate: string;
  endDate?: string | null;
  baseSalary: Money;
  currencyCode: string;
  payFrequency: PayFrequency;
  salaryBasis: SalaryBasis;
  changeReason?: string | null;
  changePct?: string | null;
  active: boolean;
  outOfGradeBand: boolean;
  createdBy: string;
  createdDate: string;
}

export interface EmployeeHistoryEntry {
  histId: number;
  empId: number;
  changeType: HistoryChangeType;
  effectiveDate: string;
  oldDeptId?: number | null;
  oldDeptName?: string | null;
  newDeptId?: number | null;
  newDeptName?: string | null;
  oldJobId?: number | null;
  oldJobTitle?: string | null;
  newJobId?: number | null;
  newJobTitle?: string | null;
  oldManagerId?: number | null;
  oldManagerName?: string | null;
  newManagerId?: number | null;
  newManagerName?: string | null;
  oldSalary?: Money | null;
  newSalary?: Money | null;
  oldLocation?: string | null;
  newLocation?: string | null;
  reasonCode?: string | null;
  comments?: string | null;
  createdBy: string;
  createdDate: string;
}

export interface Dependent {
  dependentId: number;
  empId: number;
  firstName: string;
  lastName: string;
  relationship: Relationship;
  dateOfBirth?: string | null;
  ssnLast4?: string | null;
  benefitsEnrolled: boolean;
  active: boolean;
}

export interface EmergencyContact {
  contactId: number;
  empId: number;
  contactName: string;
  relationship?: string | null;
  phonePrimary: string;
  phoneSecondary?: string | null;
  email?: string | null;
  priorityOrder: number;
  active: boolean;
}

/** `GET /api/employees` full-grid query (the `fields=id,name,jobTitle` LOV shape is `EmployeeSearchQuery`). */
export interface EmployeeListQuery {
  lastName?: string;
  firstName?: string;
  q?: string;
  deptId?: number;
  jobId?: number;
  managerEmpId?: number;
  status?: EmploymentStatus;
  active?: boolean;
  locationCode?: string;
  hireDateFrom?: string;
  hireDateTo?: string;
  page?: number;
  size?: number;
}
