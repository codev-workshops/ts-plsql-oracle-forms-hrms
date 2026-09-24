import type {
  AuditLogRow,
  Authority,
  BatchRunResult,
  Department,
  EmployeeCompensationRow,
  EmployeeDirectoryRow,
  Holiday,
  IntegrationFile,
  JobGrade,
  JobTitle,
  LeaveSummaryRow,
  LeaveType,
  Location,
  OrgHierarchyRow,
  PayElement,
  PayrollLatestRow,
  PendingApprovalRow,
  Role,
  SystemParameter,
  TaxBracket,
  UserAccount,
} from '../api/types';

/**
 * In-memory `reporting-module` / `admin-module` / `integration-module` state for the msw
 * contract mocks (contracts/p5-reporting-decommission/openapi.yaml). Names mirror
 * e2e/seed-accounts.ts and mocks/handlers.ts reference data; amounts are illustrative – the
 * PostgreSQL reconciliation packs (Level 3) are the oracle for values, this store only honours
 * the contract's shapes, sort orders and rules.
 */

const AUDIT = { createdBy: 'seed', createdDate: '2024-01-01T00:00:00Z', modifiedBy: null, modifiedDate: null } as const;

export const REPORT_AS_OF = '2024-06-30';

export const DIRECTORY_ROWS: EmployeeDirectoryRow[] = [
  { empId: 1, empNumber: 'EMP-000001', firstName: 'JAMES', lastName: 'RICHARDSON', fullName: 'JAMES RICHARDSON', email: 'james.richardson@company.com', phoneWork: null, hireDate: '2010-01-04', tenureYears: '14.4', deptId: 1, deptCode: 'HR', deptName: 'Human Resources', costCenter: 'CC100', jobId: 1, jobCode: 'CEO', jobTitle: 'Chief Executive Officer', jobFamily: 'Executive', gradeId: 5, gradeCode: 'G5', gradeName: 'Grade 5', locationCode: 'HQ', locationName: 'Headquarters', city: 'New York', stateProvince: 'NY', managerEmpId: null, managerName: null },
  { empId: 21, empNumber: 'EMP-000021', firstName: 'JENNIFER', lastName: 'PARK', fullName: 'JENNIFER PARK', email: 'jennifer.park@company.com', phoneWork: null, hireDate: '2015-03-16', tenureYears: '9.2', deptId: 3, deptCode: 'ENG', deptName: 'Engineering', costCenter: 'CC300', jobId: 3, jobCode: 'ENGMGR', jobTitle: 'Engineering Manager', jobFamily: 'Engineering', gradeId: 4, gradeCode: 'G4', gradeName: 'Grade 4', locationCode: 'SF', locationName: 'San Francisco', city: 'San Francisco', stateProvince: 'CA', managerEmpId: 1, managerName: 'JAMES RICHARDSON' },
  { empId: 11, empNumber: 'EMP-000011', firstName: 'DAVID', lastName: 'MARTINEZ', fullName: 'DAVID MARTINEZ', email: 'david.martinez@company.com', phoneWork: null, hireDate: '2019-07-01', tenureYears: '4.9', deptId: 2, deptCode: 'FIN', deptName: 'Finance', costCenter: 'CC200', jobId: 2, jobCode: 'ANALYST', jobTitle: 'Financial Analyst', jobFamily: 'Finance', gradeId: 2, gradeCode: 'G2', gradeName: 'Grade 2', locationCode: 'HQ', locationName: 'Headquarters', city: 'New York', stateProvince: 'NY', managerEmpId: 1, managerName: 'JAMES RICHARDSON' },
  { empId: 22, empNumber: 'EMP-000022', firstName: 'THOMAS', lastName: 'BAKER', fullName: 'THOMAS BAKER', email: 'thomas.baker@company.com', phoneWork: null, hireDate: '2021-02-01', tenureYears: '3.4', deptId: 3, deptCode: 'ENG', deptName: 'Engineering', costCenter: 'CC300', jobId: 4, jobCode: 'SWE', jobTitle: 'Software Engineer', jobFamily: 'Engineering', gradeId: 3, gradeCode: 'G3', gradeName: 'Grade 3', locationCode: 'SF', locationName: 'San Francisco', city: 'San Francisco', stateProvince: 'CA', managerEmpId: 21, managerName: 'JENNIFER PARK' },
  { empId: 23, empNumber: 'EMP-000023', firstName: 'LISA', lastName: 'WONG', fullName: 'LISA WONG', email: 'lisa.wong@company.com', phoneWork: null, hireDate: '2022-09-12', tenureYears: '1.8', deptId: 3, deptCode: 'ENG', deptName: 'Engineering', costCenter: 'CC300', jobId: 4, jobCode: 'SWE', jobTitle: 'Software Engineer', jobFamily: 'Engineering', gradeId: 3, gradeCode: 'G3', gradeName: 'Grade 3', locationCode: 'SF', locationName: 'San Francisco', city: 'San Francisco', stateProvince: 'CA', managerEmpId: 21, managerName: 'JENNIFER PARK' },
];

export const ORG_ROWS: OrgHierarchyRow[] = [
  { empId: 1, empNumber: 'EMP-000001', fullName: 'JAMES RICHARDSON', jobTitle: 'Chief Executive Officer', deptName: 'Human Resources', managerEmpId: null, managerName: null, orgLevel: 1, orgPath: 'JAMES RICHARDSON', isLeaf: false, directReports: 2, cycle: false },
  { empId: 11, empNumber: 'EMP-000011', fullName: 'DAVID MARTINEZ', jobTitle: 'Financial Analyst', deptName: 'Finance', managerEmpId: 1, managerName: 'JAMES RICHARDSON', orgLevel: 2, orgPath: 'JAMES RICHARDSON > DAVID MARTINEZ', isLeaf: true, directReports: 0, cycle: false },
  { empId: 21, empNumber: 'EMP-000021', fullName: 'JENNIFER PARK', jobTitle: 'Engineering Manager', deptName: 'Engineering', managerEmpId: 1, managerName: 'JAMES RICHARDSON', orgLevel: 2, orgPath: 'JAMES RICHARDSON > JENNIFER PARK', isLeaf: false, directReports: 2, cycle: false },
  { empId: 22, empNumber: 'EMP-000022', fullName: 'THOMAS BAKER', jobTitle: 'Software Engineer', deptName: 'Engineering', managerEmpId: 21, managerName: 'JENNIFER PARK', orgLevel: 3, orgPath: 'JAMES RICHARDSON > JENNIFER PARK > THOMAS BAKER', isLeaf: true, directReports: 0, cycle: false },
  { empId: 23, empNumber: 'EMP-000023', fullName: 'LISA WONG', jobTitle: 'Software Engineer', deptName: 'Engineering', managerEmpId: 21, managerName: 'JENNIFER PARK', orgLevel: 3, orgPath: 'JAMES RICHARDSON > JENNIFER PARK > LISA WONG', isLeaf: true, directReports: 0, cycle: false },
];

export const COMPENSATION_ROWS: EmployeeCompensationRow[] = [
  { empId: 1, empNumber: 'EMP-000001', fullName: 'JAMES RICHARDSON', deptId: 1, deptName: 'Human Resources', jobTitle: 'Chief Executive Officer', gradeId: 5, gradeCode: 'G5', baseSalary: '250000.00', currencyCode: 'USD', payFrequency: 'MONTHLY', effectiveDate: '2024-01-01', yearsInGrade: '0.4', minSalary: '180000.00', maxSalary: '300000.00', compaRatio: '1.0417' },
  { empId: 21, empNumber: 'EMP-000021', fullName: 'JENNIFER PARK', deptId: 3, deptName: 'Engineering', jobTitle: 'Engineering Manager', gradeId: 4, gradeCode: 'G4', baseSalary: '150000.00', currencyCode: 'USD', payFrequency: 'MONTHLY', effectiveDate: '2023-04-01', yearsInGrade: '1.2', minSalary: '120000.00', maxSalary: '180000.00', compaRatio: '1.0000' },
  { empId: 11, empNumber: 'EMP-000011', fullName: 'DAVID MARTINEZ', deptId: 2, deptName: 'Finance', jobTitle: 'Financial Analyst', gradeId: 2, gradeCode: 'G2', baseSalary: '72000.00', currencyCode: 'USD', payFrequency: 'MONTHLY', effectiveDate: '2024-01-01', yearsInGrade: '0.4', minSalary: '60000.00', maxSalary: '90000.00', compaRatio: '0.9600' },
  { empId: 22, empNumber: 'EMP-000022', fullName: 'THOMAS BAKER', deptId: 3, deptName: 'Engineering', jobTitle: 'Software Engineer', gradeId: 3, gradeCode: 'G3', baseSalary: '110000.00', currencyCode: 'USD', payFrequency: 'MONTHLY', effectiveDate: '2024-02-01', yearsInGrade: '0.4', minSalary: '90000.00', maxSalary: '130000.00', compaRatio: '1.0000' },
];

export const LEAVE_SUMMARY_ROWS: LeaveSummaryRow[] = [
  { empId: 11, empNumber: 'EMP-000011', empName: 'DAVID MARTINEZ', deptName: 'Finance', leaveTypeId: 1, leaveTypeName: 'Annual Leave', calendarYear: 2024, openingBalance: '5.00', accrued: '10.00', used: '3.00', adjustment: '0.00', pending: '2.00', available: '10.00', legacyAvailable: '12.00', utilizationPct: '20.0' },
  { empId: 22, empNumber: 'EMP-000022', empName: 'THOMAS BAKER', deptName: 'Engineering', leaveTypeId: 1, leaveTypeName: 'Annual Leave', calendarYear: 2024, openingBalance: '2.00', accrued: '10.00', used: '6.00', adjustment: '1.00', pending: '0.00', available: '7.00', legacyAvailable: '7.00', utilizationPct: '50.0' },
  { empId: 22, empNumber: 'EMP-000022', empName: 'THOMAS BAKER', deptName: 'Engineering', leaveTypeId: 2, leaveTypeName: 'Sick Leave', calendarYear: 2024, openingBalance: '0.00', accrued: '5.00', used: '0.00', adjustment: '0.00', pending: '0.00', available: '5.00', legacyAvailable: '5.00', utilizationPct: '0.0' },
];

export const PAYROLL_LATEST_ROWS: PayrollLatestRow[] = [
  { empId: 21, empNumber: 'EMP-000021', empName: 'JENNIFER PARK', deptName: 'Engineering', periodId: 202403, periodName: '2024-03 Monthly', payDate: '2024-04-05', runId: 1003, runType: 'REGULAR', runStatus: 'APPROVED', grossPay: '12500.00', totalTaxes: '3125.00', totalDeductions: '450.00', netPay: '8925.00' },
  { empId: 11, empNumber: 'EMP-000011', empName: 'DAVID MARTINEZ', deptName: 'Finance', periodId: 202403, periodName: '2024-03 Monthly', payDate: '2024-04-05', runId: 1003, runType: 'REGULAR', runStatus: 'APPROVED', grossPay: '6000.00', totalTaxes: '1200.00', totalDeductions: '200.00', netPay: '4600.00' },
];

export const PENDING_ROWS: PendingApprovalRow[] = [
  { itemType: 'LEAVE', itemId: 7001, empId: 22, empNumber: 'EMP-000022', empName: 'THOMAS BAKER', deptName: 'Engineering', approverEmpId: 21, approverName: 'JENNIFER PARK', submittedDate: '2024-06-20', daysPending: 10, detail: 'Annual Leave 2024-07-01..2024-07-05 (5.00d)' },
  { itemType: 'REVIEW', itemId: 5001, empId: 22, empNumber: 'EMP-000022', empName: 'THOMAS BAKER', deptName: 'Engineering', approverEmpId: 21, approverName: 'JENNIFER PARK', submittedDate: '2024-06-01', daysPending: 29, detail: '2024 Mid-Year Review' },
  { itemType: 'LEAVE', itemId: 7002, empId: 11, empNumber: 'EMP-000011', empName: 'DAVID MARTINEZ', deptName: 'Finance', approverEmpId: 1, approverName: 'JAMES RICHARDSON', submittedDate: '2024-06-28', daysPending: 2, detail: 'Annual Leave 2024-08-12..2024-08-13 (2.00d)' },
];

const seedDepartments = (): Department[] => [
  { deptId: 1, deptCode: 'HR', deptName: 'Human Resources', parentDeptId: null, parentDeptName: null, costCenter: 'CC100', managerEmpId: 1, managerName: 'JAMES RICHARDSON', locationCode: 'HQ', activeFlag: true, activeEmployees: 1, ...AUDIT },
  { deptId: 2, deptCode: 'FIN', deptName: 'Finance', parentDeptId: null, parentDeptName: null, costCenter: 'CC200', managerEmpId: null, managerName: null, locationCode: 'HQ', activeFlag: true, activeEmployees: 1, ...AUDIT },
  { deptId: 3, deptCode: 'ENG', deptName: 'Engineering', parentDeptId: null, parentDeptName: null, costCenter: 'CC300', managerEmpId: 21, managerName: 'JENNIFER PARK', locationCode: 'SF', activeFlag: true, activeEmployees: 3, ...AUDIT },
  { deptId: 4, deptCode: 'OLD', deptName: 'Closed Division', parentDeptId: null, parentDeptName: null, costCenter: null, managerEmpId: null, managerName: null, locationCode: null, activeFlag: false, activeEmployees: 0, ...AUDIT },
  { deptId: 5, deptCode: 'QA', deptName: 'Quality Assurance', parentDeptId: 3, parentDeptName: 'Engineering', costCenter: 'CC310', managerEmpId: null, managerName: null, locationCode: 'SF', activeFlag: true, activeEmployees: 0, ...AUDIT },
];

const seedGrades = (): JobGrade[] => [
  { gradeId: 1, gradeCode: 'G1', gradeName: 'Grade 1', minSalary: '20000.00', maxSalary: '30000.00', overtimeEligible: true, activeFlag: true, activeJobTitles: 0, ...AUDIT },
  { gradeId: 2, gradeCode: 'G2', gradeName: 'Grade 2', minSalary: '60000.00', maxSalary: '90000.00', overtimeEligible: true, activeFlag: true, activeJobTitles: 1, ...AUDIT },
  { gradeId: 3, gradeCode: 'G3', gradeName: 'Grade 3', minSalary: '90000.00', maxSalary: '130000.00', overtimeEligible: false, activeFlag: true, activeJobTitles: 1, ...AUDIT },
  { gradeId: 4, gradeCode: 'G4', gradeName: 'Grade 4', minSalary: '120000.00', maxSalary: '180000.00', overtimeEligible: false, activeFlag: true, activeJobTitles: 1, ...AUDIT },
  { gradeId: 5, gradeCode: 'G5', gradeName: 'Grade 5', minSalary: '180000.00', maxSalary: '300000.00', overtimeEligible: false, activeFlag: true, activeJobTitles: 1, ...AUDIT },
];

const seedJobTitles = (): JobTitle[] => [
  { jobId: 2, jobCode: 'ANALYST', jobTitle: 'Financial Analyst', jobFamily: 'Finance', gradeId: 2, gradeCode: 'G2', eeoCategory: null, flsaStatus: 'NON_EXEMPT', activeFlag: true, activeEmployees: 1, ...AUDIT },
  { jobId: 1, jobCode: 'CEO', jobTitle: 'Chief Executive Officer', jobFamily: 'Executive', gradeId: 5, gradeCode: 'G5', eeoCategory: '1.1', flsaStatus: 'EXEMPT', activeFlag: true, activeEmployees: 1, ...AUDIT },
  { jobId: 3, jobCode: 'ENGMGR', jobTitle: 'Engineering Manager', jobFamily: 'Engineering', gradeId: 4, gradeCode: 'G4', eeoCategory: '1.2', flsaStatus: 'EXEMPT', activeFlag: true, activeEmployees: 1, ...AUDIT },
  { jobId: 8, jobCode: 'OLDJ', jobTitle: 'Retired Job', jobFamily: 'Legacy', gradeId: 1, gradeCode: 'G1', eeoCategory: null, flsaStatus: 'EXEMPT', activeFlag: false, activeEmployees: 0, ...AUDIT },
  { jobId: 4, jobCode: 'SWE', jobTitle: 'Software Engineer', jobFamily: 'Engineering', gradeId: 3, gradeCode: 'G3', eeoCategory: '2', flsaStatus: 'EXEMPT', activeFlag: true, activeEmployees: 2, ...AUDIT },
];

const seedLocations = (): Location[] => [
  { locationCode: 'HQ', locationName: 'Headquarters', addressLine1: '1 Main St', addressLine2: null, city: 'New York', stateProvince: 'NY', postalCode: '10001', countryCode: 'US', phoneNumber: '212-555-0100', timezone: 'America/New_York', activeFlag: true, activeEmployees: 2, activeDepartments: 2, ...AUDIT },
  { locationCode: 'SF', locationName: 'San Francisco', addressLine1: '500 Market St', addressLine2: null, city: 'San Francisco', stateProvince: 'CA', postalCode: '94105', countryCode: 'US', phoneNumber: '415-555-0100', timezone: 'America/Los_Angeles', activeFlag: true, activeEmployees: 3, activeDepartments: 2, ...AUDIT },
  { locationCode: 'RMT', locationName: 'Remote', addressLine1: null, addressLine2: null, city: null, stateProvince: null, postalCode: null, countryCode: null, phoneNumber: null, timezone: 'America/New_York', activeFlag: true, activeEmployees: 0, activeDepartments: 0, ...AUDIT },
];

const seedLeaveTypes = (): LeaveType[] => [
  { leaveTypeId: 1, leaveTypeCode: 'ANNUAL', leaveTypeName: 'Annual Leave', paidFlag: true, accrualFlag: true, accrualRate: '1.67', accrualFrequency: 'MONTHLY', maxBalance: '30.00', carryoverMax: '5.00', carryoverExpiry: 3, minTenureDays: 0, requiresApproval: true, requiresDocument: false, activeFlag: true, pendingRequests: 2, ...AUDIT },
  { leaveTypeId: 2, leaveTypeCode: 'SICK', leaveTypeName: 'Sick Leave', paidFlag: true, accrualFlag: true, accrualRate: '0.83', accrualFrequency: 'MONTHLY', maxBalance: '15.00', carryoverMax: '0.00', carryoverExpiry: 0, minTenureDays: 0, requiresApproval: true, requiresDocument: true, activeFlag: true, pendingRequests: 0, ...AUDIT },
  { leaveTypeId: 3, leaveTypeCode: 'UNPAID', leaveTypeName: 'Unpaid Leave', paidFlag: false, accrualFlag: false, accrualRate: null, accrualFrequency: null, maxBalance: null, carryoverMax: null, carryoverExpiry: null, minTenureDays: 365, requiresApproval: true, requiresDocument: false, activeFlag: true, pendingRequests: 0, ...AUDIT },
];

const seedParameters = (): SystemParameter[] => [
  { paramId: 1, paramGroup: 'HR', paramCode: 'MAX_FUTURE_HIRE_DAYS', paramValue: '90', paramDescription: 'Max days a hire date may lie in the future', dataType: 'NUMBER', editableFlag: true, createdBy: 'seed', createdDate: AUDIT.createdDate, modifiedBy: null, modifiedDate: null },
  { paramId: 2, paramGroup: 'SECURITY', paramCode: 'PASSWORD_MIN_LENGTH', paramValue: '8', paramDescription: 'Minimum password length', dataType: 'NUMBER', editableFlag: true, createdBy: 'seed', createdDate: AUDIT.createdDate, modifiedBy: null, modifiedDate: null },
  { paramId: 3, paramGroup: 'SECURITY', paramCode: 'SESSION_TIMEOUT_MIN', paramValue: '30', paramDescription: 'Session idle timeout (minutes)', dataType: 'NUMBER', editableFlag: true, createdBy: 'seed', createdDate: AUDIT.createdDate, modifiedBy: null, modifiedDate: null },
  { paramId: 4, paramGroup: 'SECURITY', paramCode: 'TOKEN_TTL_MINUTES', paramValue: '15', paramDescription: 'Access token lifetime', dataType: 'NUMBER', editableFlag: false, createdBy: 'seed', createdDate: AUDIT.createdDate, modifiedBy: null, modifiedDate: null },
  { paramId: 5, paramGroup: 'PAYROLL', paramCode: 'AUTO_APPROVE', paramValue: 'N', paramDescription: 'Auto-approve calculated runs', dataType: 'BOOLEAN', editableFlag: true, createdBy: 'seed', createdDate: AUDIT.createdDate, modifiedBy: null, modifiedDate: null },
];

const seedAudit = (): AuditLogRow[] => [
  { auditId: 9003, tableName: 'employees', recordId: 22, actionType: 'UPDATE', oldValues: '{"jobId":3}', newValues: '{"jobId":4}', changedBy: 'jennifer.park@company.com', changedDate: '2024-06-15T10:12:00Z', ipAddress: '10.0.0.5', sessionId: 's-3' },
  { auditId: 9002, tableName: 'salary_records', recordId: 501, actionType: 'INSERT', oldValues: null, newValues: '{"baseSalary":"110000.00","ssn":"***-**-1234"}', changedBy: 'james.richardson@company.com', changedDate: '2024-06-10T09:00:00Z', ipAddress: '10.0.0.2', sessionId: 's-2' },
  { auditId: 9001, tableName: 'user_sessions', recordId: 77, actionType: 'LOGIN', oldValues: null, newValues: null, changedBy: 'david.martinez@company.com', changedDate: '2024-05-30T08:00:00Z', ipAddress: '10.0.0.9', sessionId: 's-1' },
];

const seedFiles = (): IntegrationFile[] => [
  { fileId: '11111111-1111-4111-8111-111111111111', feed: 'GL_JOURNAL', fileName: 'GL_JOURNAL_1003_20240405.txt', status: 'SUCCESS', sizeBytes: 412, sha256: 'a'.repeat(64), recordCount: 6, sourceRef: '1003', storageKey: 'GL_JOURNAL/2024/04/11111111-1111-4111-8111-111111111111-GL_JOURNAL_1003_20240405.txt', contentUrl: '/api/integration/files/11111111-1111-4111-8111-111111111111/content', createdBy: 'james.richardson@company.com', createdAt: '2024-04-05T12:00:00Z', message: null },
];

const seedHolidays = (): Holiday[] => [
  { holidayId: 1, holidayDate: '2024-01-01', holidayName: 'New Year\u2019s Day', locationCode: null, floatingFlag: false, observedDate: '2024-01-01', ...AUDIT, activeFlag: true },
  { holidayId: 2, holidayDate: '2024-06-19', holidayName: 'Juneteenth', locationCode: 'HQ', floatingFlag: false, observedDate: '2024-06-19', ...AUDIT, activeFlag: true },
  { holidayId: 3, holidayDate: '2024-11-28', holidayName: 'Thanksgiving', locationCode: null, floatingFlag: true, observedDate: '2024-11-28', ...AUDIT, activeFlag: true },
  { holidayId: 4, holidayDate: '2023-12-25', holidayName: 'Christmas 2023', locationCode: null, floatingFlag: false, observedDate: '2023-12-25', ...AUDIT, activeFlag: false },
];

const payElement = (elementId: number, elementCode: string, elementName: string, elementType: PayElement['elementType'], calculationType: PayElement['calculationType'], extra: Partial<PayElement> = {}): PayElement => ({
  elementId,
  elementCode,
  elementName,
  elementType,
  calculationType,
  defaultAmount: null,
  defaultPercentage: null,
  taxableFlag: elementType === 'EARNING',
  pretaxFlag: false,
  employerPaid: false,
  glAccountCode: null,
  priorityOrder: 100,
  reserved: elementId === 0 || elementId === 1 || (elementId >= 100 && elementId <= 103),
  activeEmployeeElements: 0,
  ...AUDIT,
  activeFlag: true,
  ...extra,
});

/** Reserved rows mirror `PayrollConstants` (`0` ERROR sentinel, `1` BASE_PAY, `100`–`103` taxes). */
const seedPayElements = (): PayElement[] => [
  payElement(0, 'ERROR', 'Calculation error sentinel', 'ERROR', 'FORMULA', { activeFlag: false, priorityOrder: 9999 }),
  payElement(1, 'BASE_PAY', 'Base salary', 'EARNING', 'FORMULA', { priorityOrder: 1, glAccountCode: '6000.100', activeEmployeeElements: 5 }),
  payElement(100, 'FED_TAX', 'Federal income tax', 'TAX', 'FORMULA', { priorityOrder: 500, glAccountCode: '2100' }),
  payElement(101, 'STATE_TAX', 'State income tax', 'TAX', 'FORMULA', { priorityOrder: 510, glAccountCode: '2110' }),
  payElement(102, 'FICA', 'Social security', 'TAX', 'PERCENTAGE', { defaultPercentage: '6.20', priorityOrder: 520, glAccountCode: '2120' }),
  payElement(103, 'MEDICARE', 'Medicare', 'TAX', 'PERCENTAGE', { defaultPercentage: '1.45', priorityOrder: 530, glAccountCode: '2130' }),
  payElement(200, 'HEALTH_INS', 'Health insurance', 'DEDUCTION', 'FLAT', { defaultAmount: '150.00', pretaxFlag: true, priorityOrder: 300, glAccountCode: '2200', activeEmployeeElements: 2 }),
  payElement(201, 'BONUS', 'Discretionary bonus', 'EARNING', 'FLAT', { defaultAmount: '0.00', priorityOrder: 50, glAccountCode: '6000.200' }),
  payElement(202, 'PARKING', 'Parking allowance', 'REIMBURSEMENT', 'FLAT', { defaultAmount: '75.00', priorityOrder: 400, activeFlag: false }),
];

const bracket = (bracketId: number, taxYear: number, filingStatus: TaxBracket['filingStatus'], stateCode: string | null, bracketMin: string, bracketMax: string | null, taxRate: string, baseTax: string, locked = false): TaxBracket => ({
  bracketId,
  taxYear,
  filingStatus,
  stateCode,
  bracketMin,
  bracketMax,
  taxRate,
  baseTax,
  locked,
  ...AUDIT,
  activeFlag: true,
});

/** 2024 SINGLE ladder has a deliberate gap `[50000, 60000)`; 2023 is locked by approved run 9001. */
const seedTaxBrackets = (): TaxBracket[] => [
  bracket(1, 2024, 'SINGLE', null, '0.00', '11600.00', '0.1000', '0.00'),
  bracket(2, 2024, 'SINGLE', null, '11600.00', '50000.00', '0.1200', '1160.00'),
  bracket(3, 2024, 'SINGLE', null, '60000.00', null, '0.2200', '6968.00'),
  bracket(4, 2024, 'MARRIED_JOINT', null, '0.00', '23200.00', '0.1000', '0.00'),
  bracket(5, 2024, 'MARRIED_JOINT', null, '23200.00', null, '0.1200', '2320.00'),
  bracket(6, 2024, 'ALL', 'CA', '0.00', null, '0.0930', '0.00'),
  bracket(7, 2023, 'SINGLE', null, '0.00', null, '0.1000', '0.00', true),
];

const MODULES = ['PAYROLL', 'EMPLOYEE', 'LEAVE', 'ADMIN', 'REPORTS'] as const;
const ACTIONS = ['VIEW', 'EDIT', 'APPROVE', 'CREATE'] as const;

/** `GET /api/admin/authorities` – exactly the `@PreAuthorize` vocabulary (openapi `Authority`). */
export const AUTHORITY_VOCABULARY: Authority[] = [
  ...MODULES.flatMap((m) => ACTIONS.map((a) => `${m}:${a}` as Authority)),
  'LEAVE:ADMIN',
  'LEAVE:VIEW_ALL',
  'PERFORMANCE:VIEW',
  'PERFORMANCE:EDIT',
  'PERFORMANCE:APPROVE',
  'PERFORMANCE:CREATE',
  'PERFORMANCE:ADMIN',
].sort() as Authority[];

const SEEDED_ROLE = { seeded: true, createdBy: 'SYSTEM', createdDate: '2024-01-01T00:00:00Z' } as const;

const seedRoles = (): Role[] => [
  { roleId: 1, roleCode: 'STAFF', roleName: 'Staff', minGrade: 1, maxGrade: 3, permissions: ['EMPLOYEE:VIEW', 'LEAVE:VIEW', 'LEAVE:CREATE'], userCount: 3, ...SEEDED_ROLE },
  { roleId: 2, roleCode: 'MANAGER', roleName: 'Manager', minGrade: 4, maxGrade: 6, permissions: ['PAYROLL:VIEW', 'EMPLOYEE:VIEW', 'LEAVE:VIEW', 'ADMIN:VIEW', 'REPORTS:VIEW', 'LEAVE:CREATE', 'PERFORMANCE:VIEW'], userCount: 1, ...SEEDED_ROLE },
  { roleId: 3, roleCode: 'EXECUTIVE', roleName: 'Executive', minGrade: 7, maxGrade: 999, permissions: AUTHORITY_VOCABULARY, userCount: 1, ...SEEDED_ROLE },
  { roleId: 1000, roleCode: 'AUDITOR', roleName: 'Read-only auditor', minGrade: 1, maxGrade: 999, permissions: ['ADMIN:VIEW', 'REPORTS:VIEW'], userCount: 0, seeded: false, createdBy: 'ua-1', createdDate: '2024-05-01T09:00:00Z' },
];

const grant = (roleId: number, roleCode: string, roleName: string) => ({ roleId, roleCode, roleName, grantedBy: 'SYSTEM', grantedDate: '2024-01-01T00:00:00Z' });

/** Mirrors mocks/handlers.ts MOCK_USERS (`ua-N` → `userId N`). `authorities` is recomputed from roles on write. */
const seedUsers = (): UserAccount[] => [
  { userId: 1, empId: 1, empNumber: 'EMP-000001', fullName: 'JAMES RICHARDSON', username: 'james.richardson@company.com', status: 'ACTIVE', locked: false, lockedUntil: null, failedAttempts: 0, mustChangePassword: false, passwordChangedAt: '2024-01-01T00:00:00Z', roles: [grant(3, 'EXECUTIVE', 'Executive')], authorities: [], createdBy: 'SYSTEM', createdDate: '2024-01-01T00:00:00Z', modifiedBy: null, modifiedDate: null },
  { userId: 2, empId: 21, empNumber: 'EMP-000021', fullName: 'JENNIFER PARK', username: 'jennifer.park@company.com', status: 'ACTIVE', locked: false, lockedUntil: null, failedAttempts: 0, mustChangePassword: false, passwordChangedAt: '2024-01-01T00:00:00Z', roles: [grant(2, 'MANAGER', 'Manager')], authorities: [], createdBy: 'SYSTEM', createdDate: '2024-01-01T00:00:00Z', modifiedBy: null, modifiedDate: null },
  { userId: 3, empId: 11, empNumber: 'EMP-000011', fullName: 'DAVID MARTINEZ', username: 'david.martinez@company.com', status: 'ACTIVE', locked: false, lockedUntil: null, failedAttempts: 0, mustChangePassword: false, passwordChangedAt: '2024-01-01T00:00:00Z', roles: [grant(1, 'STAFF', 'Staff')], authorities: [], createdBy: 'SYSTEM', createdDate: '2024-01-01T00:00:00Z', modifiedBy: null, modifiedDate: null },
  { userId: 4, empId: 12, empNumber: 'EMP-000012', fullName: 'EMILY JOHNSON', username: 'emily.johnson@company.com', status: 'ACTIVE', locked: true, lockedUntil: '2099-01-01T00:00:00Z', failedAttempts: 5, mustChangePassword: true, passwordChangedAt: null, roles: [grant(1, 'STAFF', 'Staff')], authorities: [], createdBy: 'SYSTEM', createdDate: '2024-01-01T00:00:00Z', modifiedBy: null, modifiedDate: null },
  { userId: 5, empId: 2, empNumber: 'EMP-000002', fullName: 'SARAH CHEN', username: 'sarah.chen@company.com', status: 'ACTIVE', locked: false, lockedUntil: null, failedAttempts: 0, mustChangePassword: false, passwordChangedAt: '2024-01-01T00:00:00Z', roles: [grant(1, 'STAFF', 'Staff')], authorities: [], createdBy: 'SYSTEM', createdDate: '2024-01-01T00:00:00Z', modifiedBy: null, modifiedDate: null },
];

export function effectiveAuthorities(user: UserAccount, roles: Role[]): Authority[] {
  const set = new Set<Authority>();
  for (const g of user.roles) for (const a of roles.find((r) => r.roleId === g.roleId)?.permissions ?? []) set.add(a);
  return [...set].sort();
}

export interface P5State {
  holidays: Holiday[];
  payElements: PayElement[];
  taxBrackets: TaxBracket[];
  roles: Role[];
  users: UserAccount[];
  roleSeq: number;
  departments: Department[];
  grades: JobGrade[];
  jobTitles: JobTitle[];
  locations: Location[];
  leaveTypes: LeaveType[];
  parameters: SystemParameter[];
  audit: AuditLogRow[];
  jobs: BatchRunResult[];
  files: IntegrationFile[];
  seq: number;
}

export let p5: P5State = fresh();

function fresh(): P5State {
  const roles = seedRoles();
  const users = seedUsers().map((u) => ({ ...u, authorities: effectiveAuthorities(u, roles) }));
  return {
    holidays: seedHolidays(),
    payElements: seedPayElements(),
    taxBrackets: seedTaxBrackets(),
    roles,
    users,
    roleSeq: 1000,
    departments: seedDepartments(),
    grades: seedGrades(),
    jobTitles: seedJobTitles(),
    locations: seedLocations(),
    leaveTypes: seedLeaveTypes(),
    parameters: seedParameters(),
    audit: seedAudit(),
    jobs: [],
    files: seedFiles(),
    seq: 100,
  };
}

export function resetP5State() {
  p5 = fresh();
}

export function nextId(): number {
  p5.seq += 1;
  return p5.seq;
}

export function uuid(): string {
  const n = nextId().toString(16).padStart(12, '0');
  return `00000000-0000-4000-8000-${n}`;
}

export function clone<T>(v: T): T {
  return JSON.parse(JSON.stringify(v)) as T;
}
