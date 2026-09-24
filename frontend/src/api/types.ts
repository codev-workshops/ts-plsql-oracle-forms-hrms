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

// ---------------------------------------------------------------------------
// Phase 3 – contracts/p3-employee/openapi.yaml (employee-service + salary-module)
// ---------------------------------------------------------------------------

/** Decimal string with exactly two decimals (`NUMBER(12,2)`); never a float. */
export type Money = string;

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

/** `GET /api/employees` without `fields` → `PageOfEmployeeListItem`. */
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

/** Full replacement of the editable set; `ssn` absent/null = unchanged. */
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

/** `VW_EMPLOYEE_DETAILS` semantics; never contains SSN, photo or salary. */
export interface EmployeeDetail {
  id: number;
  empNumber: string;
  firstName: string;
  middleName: string | null;
  lastName: string;
  dateOfBirth: string | null;
  gender: Gender | null;
  maritalStatus: string | null;
  nationality: string | null;
  ssnLast4: string | null;
  email: string | null;
  phoneWork: string | null;
  phoneMobile: string | null;
  addressLine1: string | null;
  addressLine2: string | null;
  city: string | null;
  stateProvince: string | null;
  postalCode: string | null;
  countryCode: string | null;
  hireDate: string;
  terminationDate: string | null;
  terminationReason: string | null;
  deptId: number;
  deptName: string;
  jobId: number;
  jobTitle: string;
  gradeCode: string | null;
  managerEmpId: number | null;
  managerName: string | null;
  locationCode: string | null;
  locationName: string | null;
  employmentType: EmploymentType;
  employmentStatus: EmploymentStatus;
  active: boolean;
  notes: string | null;
  version: number;
  createdBy: string;
  createdDate: string;
  modifiedBy: string | null;
  modifiedDate: string | null;
}

export interface EmployeeListItem {
  id: number;
  empNumber: string;
  firstName: string;
  lastName: string;
  email: string | null;
  deptId: number;
  deptName: string;
  jobId: number;
  jobTitle: string;
  managerEmpId: number | null;
  managerName: string | null;
  locationCode: string | null;
  hireDate: string;
  employmentType: EmploymentType;
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
  endDate: string | null;
  baseSalary: Money;
  currencyCode: string;
  payFrequency: PayFrequency;
  salaryBasis: SalaryBasis;
  changeReason: string | null;
  changePct: string | null;
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
  oldDeptId: number | null;
  oldDeptName: string | null;
  newDeptId: number | null;
  newDeptName: string | null;
  oldJobId: number | null;
  oldJobTitle: string | null;
  newJobId: number | null;
  newJobTitle: string | null;
  oldManagerId: number | null;
  oldManagerName: string | null;
  newManagerId: number | null;
  newManagerName: string | null;
  oldSalary: Money | null;
  newSalary: Money | null;
  oldLocation: string | null;
  newLocation: string | null;
  reasonCode: string | null;
  comments: string | null;
  createdBy: string;
  createdDate: string;
}

export interface Dependent {
  dependentId: number;
  empId: number;
  firstName: string;
  lastName: string;
  relationship: Relationship;
  dateOfBirth: string | null;
  ssnLast4: string | null;
  benefitsEnrolled: boolean;
  active: boolean;
}

export interface EmergencyContact {
  contactId: number;
  empId: number;
  contactName: string;
  relationship: string | null;
  phonePrimary: string;
  phoneSecondary: string | null;
  email: string | null;
  priorityOrder: number;
  active: boolean;
}

/** `GET …/{id}` returns the ETag the caller must echo as `If-Match` on `PUT`. */
export interface EmployeeDetailWithEtag {
  employee: EmployeeDetail;
  etag: string;
}

// ---------------------------------------------------------------------------------------
// contracts/p4-payroll/openapi.yaml – payroll-module (pure-Java TaxEngine / PayrollRunService)
// ---------------------------------------------------------------------------------------

export type PeriodStatus = 'OPEN' | 'PROCESSING' | 'CLOSED' | 'REVERSED';
export type RunType = 'REGULAR' | 'SUPPLEMENTAL' | 'BONUS' | 'FINAL';
export type RunStatus = 'PENDING' | 'CALCULATING' | 'CALCULATED' | 'APPROVED' | 'PAID' | 'REVERSED' | 'ERROR';
export type DetailStatus = 'CALCULATED' | 'ERROR' | 'REVERSED';
export type ElementType = 'EARNING' | 'DEDUCTION' | 'TAX' | 'BENEFIT' | 'ERROR';
export type FilingStatus = 'SINGLE' | 'MARRIED_JOINT' | 'MARRIED_SEPARATE' | 'HEAD_OF_HOUSEHOLD';

export type PayPeriodSort = `${'periodStartDate' | 'periodEndDate' | 'payDate' | 'periodName'},${'asc' | 'desc'}`;

export interface PayPeriodListQuery {
  status?: PeriodStatus;
  sort?: PayPeriodSort;
  page?: number;
  size?: number;
}

export interface PayrollRunListQuery {
  status?: RunStatus;
}

export interface PayrollDetailListQuery {
  empId?: number;
  status?: DetailStatus;
  page?: number;
  size?: number;
}

export interface PayrollRunCreateRequest {
  runType: RunType;
}

export interface PayrollRunReverseRequest {
  reason: string;
}

export interface PayPeriod {
  periodId: number;
  periodName: string;
  payFrequency: PayFrequency;
  periodStartDate: string;
  periodEndDate: string;
  payDate: string;
  status: PeriodStatus;
  closedBy?: string | null;
  closedDate?: string | null;
  runCount: number;
  latestRunId?: number | null;
  latestRunStatus?: RunStatus | null;
}

export interface PageOfPayPeriod {
  content: PayPeriod[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface PayrollRun {
  runId: number;
  periodId: number;
  runType: RunType;
  runDate: string;
  status: RunStatus;
  totalGross: Money;
  /** Positive magnitude of taxes + deductions + benefits. */
  totalDeductions: Money;
  totalNet: Money;
  /** Never computed in P4; always `null`. */
  totalEmployerCost?: Money | null;
  employeeCount: number;
  errorCount: number;
  engine: 'JAVA';
  submittedBy?: string | null;
  submittedDate?: string | null;
  approvedBy?: string | null;
  approvedDate?: string | null;
  createdBy?: string;
  createdDate?: string;
}

export interface PayrollRunStatus {
  runId: number;
  status: RunStatus;
  jobExecutionId?: number | null;
  processed: number;
  /** Population size; `null` until resolved. */
  total: number | null;
  errorCount: number;
  startedAt?: string | null;
  finishedAt?: string | null;
  /** Set only when the batch job itself failed and the run is `ERROR`. */
  failureMessage?: string | null;
}

export interface PayrollApprovalWarning {
  empId: number;
  empNumber: string;
  errorCode: string;
  errorMessage: string;
}

export interface PayrollRunApproval {
  run: PayrollRun;
  warnings: PayrollApprovalWarning[];
}

export interface PayrollDetail {
  detailId: number;
  runId: number;
  empId: number;
  empNumber: string;
  /** `1` base pay, `100` FED_TAX, `101` STATE_TAX, `102` FICA, `103` MEDICARE, `0` error sentinel. */
  elementId: number;
  elementCode: string;
  elementType: ElementType;
  hoursWorked?: string | null;
  rate?: string | null;
  /** Stored sign: `+` earnings, `−` taxes/deductions/benefits, `0.00` on ERROR rows. */
  amount: Money;
  ytdAmount?: Money | null;
  status: DetailStatus;
  errorCode?: string | null;
  errorMessage?: string | null;
}

export interface PageOfPayrollDetail {
  content: PayrollDetail[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface Payslip {
  runId: number;
  empId: number;
  empNumber: string;
  empName: string;
  departmentName?: string | null;
  jobTitle?: string | null;
  periodName: string;
  periodStartDate: string;
  periodEndDate: string;
  payDate: string;
  runStatus: RunStatus;
  grossPay: Money;
  federalTax: Money;
  stateTax: Money;
  socialSecurity: Money;
  medicare: Money;
  otherDeductions: Money;
  totalDeductions: Money;
  netPay: Money;
  ytdGross: Money;
  ytdTaxes: Money;
  ytdDeductions: Money;
  ytdNet: Money;
  lines: PayrollDetail[];
}

export type ShadowClassification = 'MATCH' | 'DIFF_EXPLAINED' | 'DIFF_UNEXPLAINED' | 'LEGACY_ONLY' | 'JAVA_ONLY';
export type ShadowExplanation = 'UNLISTED_STATE_FALLBACK' | 'HEAD_OF_HOUSEHOLD_ZERO_FED' | 'NON_2024_YEAR' | 'LEGACY_PARTIAL_COMMIT' | 'LEGACY_ERROR_ROW';

export interface ShadowDiffLine {
  empId: number;
  empNumber?: string;
  elementId: number;
  elementCode?: string;
  classification: ShadowClassification;
  explanation?: ShadowExplanation | null;
  legacyAmount: Money | null;
  javaAmount: Money | null;
  deltaCents: number;
}

export interface ShadowDiffReport {
  runId: number;
  periodId: number;
  taxYear: number;
  engineFlag: 'LEGACY' | 'JAVA';
  legacySource: 'oracle-cdc' | 'recorded';
  comparedAt: string;
  summary: {
    employees: number;
    matched: number;
    explained: number;
    unexplained: number;
    legacyOnly: number;
    javaOnly: number;
    errorRowsLegacy: number;
    errorRowsJava: number;
    netDeltaCents: number;
  };
  lines: ShadowDiffLine[];
}

/** `GET …/register.csv` – the streamed body plus the server-chosen filename. */
export interface PayrollRegisterDownload {
  filename: string;
  csv: string;
}

// ---------------------------------------------------------------------------
// contracts/p5-reporting-decommission/openapi.yaml (components.schemas)
// ---------------------------------------------------------------------------

/** Four-decimal ratio (`compaRatio`). */
export type Rate = string;
/** Leave days with two decimals (`NUMBER(6,2)`). */
export type Days = string;
/** `TRUNC(MONTHS_BETWEEN(...)/12, 1)` – one decimal. */
export type Years = string;
/** `ROUND(x*100/NULLIF(denominator,0), 1)`; null when the denominator is 0. */
export type Percent = string | null;

export interface PageMeta {
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface AuditColumns {
  activeFlag: boolean;
  createdBy: string;
  createdDate: string;
  modifiedBy?: string | null;
  modifiedDate?: string | null;
}

/** `GET …/{report}.csv` – the streamed body plus the server-chosen filename. */
export interface CsvDownload {
  filename: string;
  csv: string;
}

// --- reports ---------------------------------------------------------------
export interface EmployeeDirectoryQuery {
  asOf?: string;
  deptId?: number;
  locationCode?: string;
  page?: number;
  size?: number;
}

export interface EmployeeDirectoryRow {
  empId: number;
  empNumber: string;
  firstName: string;
  lastName: string;
  fullName: string;
  email?: string | null;
  phoneWork?: string | null;
  hireDate: string;
  tenureYears: Years;
  deptId: number;
  deptCode: string;
  deptName: string;
  costCenter?: string | null;
  jobId: number;
  jobCode: string;
  jobTitle: string;
  jobFamily?: string | null;
  gradeId: number;
  gradeCode: string;
  gradeName: string;
  locationCode?: string | null;
  locationName?: string | null;
  city?: string | null;
  stateProvince?: string | null;
  managerEmpId?: number | null;
  managerName?: string | null;
}

export interface HeadcountByDepartment {
  deptId: number;
  deptCode: string;
  deptName: string;
  locationCode?: string | null;
  headcount: number;
  avgTenureYears: Years;
}

export interface EmployeeDirectoryPage {
  asOf: string;
  content: EmployeeDirectoryRow[];
  page: PageMeta;
  summary: { totalHeadcount: number; headcountByDepartment: HeadcountByDepartment[] };
}

export interface OrgHierarchyQuery {
  asOf?: string;
  rootEmpId?: number;
  maxLevel?: number;
  page?: number;
  size?: number;
}

export interface OrgHierarchyRow {
  empId: number;
  empNumber: string;
  fullName: string;
  jobTitle: string;
  deptName: string;
  managerEmpId?: number | null;
  managerName?: string | null;
  orgLevel: number;
  orgPath: string;
  isLeaf: boolean;
  directReports?: number;
  cycle: boolean;
}

export interface OrgHierarchyPage {
  asOf: string;
  content: OrgHierarchyRow[];
  page: PageMeta;
}

export interface EmployeeCompensationQuery {
  asOf?: string;
  deptId?: number;
  gradeId?: number;
  page?: number;
  size?: number;
}

export interface EmployeeCompensationRow {
  empId: number;
  empNumber: string;
  fullName: string;
  deptId?: number;
  deptName: string;
  jobTitle: string;
  gradeId?: number;
  gradeCode: string;
  baseSalary: Money;
  currencyCode: string;
  payFrequency: string;
  effectiveDate: string;
  yearsInGrade: Years;
  minSalary: Money;
  maxSalary: Money;
  compaRatio: Rate;
}

export interface CompensationByDepartment {
  deptId: number;
  deptName: string;
  headcount: number;
  avgSalary: Money;
  minSalary: Money;
  maxSalary: Money;
  totalPayroll: Money;
}

export interface EmployeeCompensationPage {
  asOf: string;
  content: EmployeeCompensationRow[];
  page: PageMeta;
  summary: { byDepartment: CompensationByDepartment[] };
}

export interface LeaveSummaryQuery {
  asOf?: string;
  year?: number;
  deptId?: number;
  leaveTypeId?: number;
  page?: number;
  size?: number;
}

export interface LeaveSummaryRow {
  empId: number;
  empNumber: string;
  empName: string;
  deptName: string;
  leaveTypeId: number;
  leaveTypeName: string;
  calendarYear: number;
  openingBalance: Days;
  accrued: Days;
  used: Days;
  adjustment: Days;
  pending: Days;
  /** `openingBalance + accrued - used + adjustment - pending` (VAL-05). */
  available: Days;
  utilizationPct: Percent;
  /** Legacy view value (`available` without `- pending`), JSON only. */
  legacyAvailable?: Days;
}

export interface LeaveUtilizationByType {
  leaveTypeId: number;
  leaveTypeName: string;
  employees: number;
  totalAccrued: Days;
  totalUsed: Days;
  avgUtilizationPct: Percent;
}

export interface LeaveSummaryPage {
  asOf: string;
  year: number;
  content: LeaveSummaryRow[];
  page: PageMeta;
  summary: { byLeaveType: LeaveUtilizationByType[] };
}

export interface PayrollLatestQuery {
  periodId?: number;
  deptId?: number;
  page?: number;
  size?: number;
}

export interface PayrollLatestRow {
  empId: number;
  empNumber: string;
  empName: string;
  deptName: string;
  periodId: number;
  periodName: string;
  payDate: string;
  runId: number;
  runType: RunType;
  runStatus: 'APPROVED' | 'PAID';
  grossPay: Money;
  totalTaxes: Money;
  totalDeductions: Money;
  netPay: Money;
}

export interface PayrollSummary {
  periodId: number;
  employeeCount: number;
  totalGross: Money;
  totalTaxes: Money;
  totalDeductions: Money;
  totalNet: Money;
  avgNet: Money;
}

export interface PayrollLatestPage {
  content: PayrollLatestRow[];
  page: PageMeta;
  summary: PayrollSummary;
}

export type PendingItemType = 'LEAVE' | 'REVIEW';

export interface PendingApprovalsQuery {
  asOf?: string;
  itemType?: PendingItemType;
  /** Restrict to items awaiting the caller (JWT `empId`); never an employee id. */
  mine?: boolean;
  deptId?: number;
  page?: number;
  size?: number;
}

export interface PendingApprovalRow {
  itemType: PendingItemType;
  itemId: number;
  empId: number;
  empNumber?: string;
  empName: string;
  deptName: string;
  approverEmpId?: number | null;
  approverName?: string | null;
  submittedDate: string;
  daysPending: number;
  detail: string;
}

export interface PendingApprovalPage {
  asOf: string;
  content: PendingApprovalRow[];
  page: PageMeta;
  summary: { leave: number; review: number };
}

// --- admin reference data ----------------------------------------------------
export interface ActiveFilterQuery {
  active?: boolean;
}

export interface DepartmentRequest {
  deptCode: string;
  deptName: string;
  parentDeptId?: number | null;
  costCenter?: string | null;
  managerEmpId?: number | null;
  locationCode?: string | null;
  activeFlag?: boolean;
}

export interface Department extends Omit<DepartmentRequest, 'activeFlag'>, AuditColumns {
  deptId: number;
  parentDeptName?: string | null;
  managerName?: string | null;
  activeEmployees?: number;
}

export interface JobGradeRequest {
  gradeCode: string;
  gradeName: string;
  minSalary: Money;
  /** `>= minSalary` (`CHK_SALARY_RANGE`, `-20603`) – enforced by the server. */
  maxSalary: Money;
  overtimeEligible?: boolean;
  activeFlag?: boolean;
}

export interface JobGrade extends Omit<JobGradeRequest, 'activeFlag'>, AuditColumns {
  gradeId: number;
  activeJobTitles?: number;
}

export type FlsaStatus = 'EXEMPT' | 'NON_EXEMPT';

export interface JobTitleRequest {
  jobCode: string;
  jobTitle: string;
  jobFamily?: string | null;
  gradeId: number;
  eeoCategory?: string | null;
  flsaStatus?: FlsaStatus;
  activeFlag?: boolean;
}

export interface JobTitle extends Omit<JobTitleRequest, 'activeFlag'>, AuditColumns {
  jobId: number;
  gradeCode: string;
  activeEmployees?: number;
}

export interface LocationRequest {
  locationCode: string;
  locationName: string;
  addressLine1?: string | null;
  addressLine2?: string | null;
  city?: string | null;
  stateProvince?: string | null;
  postalCode?: string | null;
  countryCode?: string | null;
  phoneNumber?: string | null;
  timezone?: string;
  activeFlag?: boolean;
}

export interface Location extends Omit<LocationRequest, 'activeFlag'>, AuditColumns {
  activeEmployees?: number;
  activeDepartments?: number;
}

export type AccrualFrequency = 'MONTHLY' | 'BIWEEKLY' | 'ANNUAL';

export interface LeaveTypeRequest {
  leaveTypeCode: string;
  leaveTypeName: string;
  paidFlag?: boolean;
  accrualFlag?: boolean;
  accrualRate?: string | null;
  accrualFrequency?: AccrualFrequency | null;
  maxBalance?: string | null;
  /** `<= maxBalance` when both set (`-20603`) – enforced by the server. */
  carryoverMax?: string | null;
  carryoverExpiry?: number | null;
  minTenureDays?: number;
  requiresApproval?: boolean;
  requiresDocument?: boolean;
  activeFlag?: boolean;
}

export interface LeaveType extends Omit<LeaveTypeRequest, 'activeFlag'>, AuditColumns {
  leaveTypeId: number;
  pendingRequests?: number;
}

export type ParamDataType = 'VARCHAR2' | 'NUMBER' | 'DATE' | 'BOOLEAN';

export interface SystemParameterRequest {
  paramGroup: string;
  paramCode: string;
  paramValue: string;
  paramDescription?: string | null;
  dataType: ParamDataType;
  editableFlag?: boolean;
}

export interface SystemParameterUpdateRequest {
  paramValue: string;
  paramDescription?: string | null;
}

export interface SystemParameter extends SystemParameterRequest {
  paramId: number;
  createdBy: string;
  createdDate: string;
  modifiedBy?: string | null;
  modifiedDate?: string | null;
}

// --- admin payroll reference (§9.2 expansion) --------------------------------
export interface HolidayRequest {
  holidayDate: string;
  holidayName: string;
  /** null = company-wide; otherwise an active `LOCATIONS` row (`-20604`). */
  locationCode?: string | null;
  floatingFlag?: boolean;
  activeFlag?: boolean;
}

export interface Holiday extends Omit<HolidayRequest, 'activeFlag'>, AuditColumns {
  holidayId: number;
  /** Weekend shift applied by `BusinessCalendar` (Sat → Fri, Sun → Mon); derived. */
  observedDate?: string;
}

export interface HolidayListQuery extends ActiveFilterQuery {
  year?: number;
  locationCode?: string;
}

export type PayElementType = 'EARNING' | 'DEDUCTION' | 'TAX' | 'BENEFIT' | 'REIMBURSEMENT' | 'ERROR';
export type CalculationType = 'FLAT' | 'PERCENTAGE' | 'HOURS' | 'FORMULA';

export interface PayElementRequest {
  elementCode: string;
  elementName: string;
  elementType: PayElementType;
  calculationType: CalculationType;
  /** `calculationType` × defaults rules are `-20603` on the server. */
  defaultAmount?: string | null;
  defaultPercentage?: string | null;
  taxableFlag?: boolean;
  pretaxFlag?: boolean;
  employerPaid?: boolean;
  glAccountCode?: string | null;
  priorityOrder?: number;
  activeFlag?: boolean;
}

export interface PayElement extends Omit<PayElementRequest, 'activeFlag'>, AuditColumns {
  elementId: number;
  /** `0`, `1`, `100`–`103`: `-20607` rules apply. */
  reserved: boolean;
  activeEmployeeElements?: number;
}

export interface PayElementListQuery extends ActiveFilterQuery {
  elementType?: PayElementType;
}

/** Admin tax-bracket filing status: P4 `FilingStatus` plus `ALL` for state flat rows. */
export type TaxFilingStatus = FilingStatus | 'ALL';

export interface TaxBracketRequest {
  taxYear: number;
  filingStatus: TaxFilingStatus;
  /** null = federal ladder step; two-letter code = state flat row. */
  stateCode?: string | null;
  bracketMin: Money;
  bracketMax?: string | null;
  /** Fraction in `[0, 1]`, four decimals. */
  taxRate: string;
  baseTax?: Money;
  activeFlag?: boolean;
}

export interface TaxBracket extends Omit<TaxBracketRequest, 'activeFlag'>, AuditColumns {
  bracketId: number;
  /** `taxYear` has an APPROVED/PAID run (`-20609`). */
  locked: boolean;
}

export interface TaxBracketListQuery extends ActiveFilterQuery {
  taxYear?: number;
  /** `FEDERAL` or a two-letter state code. */
  stateCode?: string;
  filingStatus?: TaxFilingStatus;
}

export interface TaxLadderGap {
  taxYear: number;
  filingStatus: TaxFilingStatus;
  gaps: { from: Money; to: string | null }[];
}

// --- admin role management (auth-owned tables, /api/admin prefix) --------------
export interface RoleRequest {
  roleCode: string;
  roleName: string;
  minGrade: number;
  maxGrade: number;
  /** Non-empty, distinct, each in `GET /api/admin/authorities`; least privilege `-20806`. */
  permissions: Authority[];
}

export interface Role extends RoleRequest {
  roleId: number;
  /** `1`–`3` (STAFF / MANAGER / EXECUTIVE) are read-only (`-20802`). */
  seeded: boolean;
  userCount: number;
  createdBy: string;
  createdDate: string;
}

export interface RoleWriteResult extends Role {
  sessionsRevoked: number;
}

export type AccountStatus = 'ACTIVE' | 'DISABLED';

export interface UserRoleGrant {
  roleId: number;
  roleCode: string;
  roleName: string;
  grantedBy: string;
  grantedDate: string;
}

export interface UserAccount {
  userId: number;
  empId: number;
  empNumber: string;
  fullName: string;
  username: string;
  status: AccountStatus;
  locked: boolean;
  lockedUntil?: string | null;
  failedAttempts?: number;
  mustChangePassword: boolean;
  passwordChangedAt?: string | null;
  roles: UserRoleGrant[];
  /** Effective set = union of the roles' permissions. */
  authorities: Authority[];
  createdBy: string;
  createdDate: string;
  modifiedBy?: string | null;
  modifiedDate?: string | null;
}

export interface UserAccountWriteResult extends UserAccount {
  sessionsRevoked: number;
}

export interface UserAccountSearchQuery {
  q?: string;
  status?: AccountStatus;
  roleId?: number;
  locked?: boolean;
  page?: number;
  size?: number;
}

export interface UserAccountPage {
  content: UserAccount[];
  page: PageMeta;
}

export interface UserRolesRequest {
  roleIds: number[];
}

export interface UserStatusRequest {
  status: AccountStatus;
  reason?: string | null;
}

// --- admin leave jobs --------------------------------------------------------
export interface AccrualRunRequest {
  accrualDate?: string;
}

export interface CarryoverRunRequest {
  year: number;
}

export type BatchJobType = 'ACCRUAL' | 'CARRYOVER';
export type BatchJobStatus = 'RUNNING' | 'COMPLETED' | 'FAILED';

export interface BatchRunResult {
  jobId: string;
  jobType: BatchJobType;
  status: BatchJobStatus;
  processed: number;
  skipped: number;
  failed: number;
  startedAt: string;
  finishedAt?: string | null;
  startedBy: string;
  message?: string | null;
}

// --- audit -------------------------------------------------------------------
export type AuditActionType = 'INSERT' | 'UPDATE' | 'DELETE' | 'STATUS_CHANGE' | 'LOGIN' | 'LOGOUT';

export interface AuditLogSearchQuery {
  tableName?: string;
  recordId?: number;
  actionType?: AuditActionType;
  changedBy?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

export interface AuditLogRow {
  auditId: number;
  tableName: string;
  recordId: number;
  actionType: AuditActionType;
  oldValues?: string | null;
  newValues?: string | null;
  changedBy: string;
  changedDate: string;
  ipAddress?: string | null;
  sessionId?: string | null;
}

export interface AuditLogPage {
  content: AuditLogRow[];
  page: PageMeta;
}

// --- integration -------------------------------------------------------------
export type FeedType = 'GL_JOURNAL' | 'BENEFITS_FEED' | 'TIME_ATTENDANCE';
export type IntegrationFileStatus = 'SUCCESS' | 'FAILED' | 'STAGED';

export interface GlFeedRequest {
  runId: number;
}

export interface BenefitsFeedRequest {
  effectiveDate?: string;
}

export interface IntegrationFile {
  fileId: string;
  feed: FeedType;
  fileName: string;
  status: IntegrationFileStatus;
  sizeBytes: number;
  sha256: string;
  recordCount: number;
  sourceRef?: string | null;
  storageKey?: string;
  contentUrl: string;
  createdBy: string;
  createdAt: string;
  message?: string | null;
}

export interface IntegrationFileListQuery {
  feed?: FeedType;
  status?: IntegrationFileStatus;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

export interface IntegrationFilePage {
  content: IntegrationFile[];
  page: PageMeta;
}

export interface TimeAttendanceLine {
  line: number;
  empNumber: string;
  empId?: number | null;
  workDate: string;
  hoursRegular: string;
  hoursOvertime: string;
  overtimeEligible?: boolean | null;
  verdict: 'ACCEPTED' | 'REJECTED';
  error?: ApiError | null;
}

/** BUG-08: `applied` is always false, `targetTable` / `payElementMapping` are unspecified (null). */
export interface TimeAttendanceImportResult {
  file: IntegrationFile;
  accepted: number;
  rejected: number;
  lines: TimeAttendanceLine[];
  applied: false;
  targetTable: null;
  payElementMapping: null;
}

export interface IntegrationStatus {
  feed: FeedType;
  status: 'NEVER_RUN' | 'SUCCESS' | 'FAILED' | 'STAGED';
  lastRunAt?: string | null;
  lastFileId?: string | null;
  lastRunBy?: string | null;
  message?: string | null;
}
