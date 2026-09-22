import type {
  DetailStatus,
  Money,
  PayPeriod,
  PayrollDetail,
  PayrollRun,
  PayrollRunStatus,
  Payslip,
  RunType,
  ShadowDiffReport,
} from '../api/types';

/**
 * In-memory `payroll-module` state for the msw contract mocks (contracts/p4-payroll/openapi.yaml).
 * Employee ids/names mirror e2e/seed-accounts.ts and mocks/employeeStore.ts. Amounts are
 * illustrative stand-ins: the real TaxEngine (Level 1 golden tests, backend) is the oracle for
 * cents – this store only has to honour the contract's shapes, sign convention and state machine.
 */

export interface PayrollEmployee {
  empId: number;
  empNumber: string;
  firstName: string;
  lastName: string;
  departmentName: string;
  jobTitle: string;
  /** Annual base salary; `null` = no active SALARY_RECORDS row → `-20104` ERROR row. */
  annualSalary: Money | null;
  stateCode: string | null;
  bank: { bankName: string; routingLast4: string; accountLast4: string } | null;
}

export const PAYROLL_EMPLOYEES: PayrollEmployee[] = [
  { empId: 1, empNumber: 'EMP-000001', firstName: 'JAMES', lastName: 'RICHARDSON', departmentName: 'Human Resources', jobTitle: 'CEO', annualSalary: '250000.00', stateCode: 'NY', bank: { bankName: 'First National', routingLast4: '0021', accountLast4: '4471' } },
  { empId: 11, empNumber: 'EMP-000011', firstName: 'DAVID', lastName: 'MARTINEZ', departmentName: 'Finance', jobTitle: 'Analyst', annualSalary: '60000.00', stateCode: 'NY', bank: { bankName: 'Metro Credit Union', routingLast4: '7730', accountLast4: '1298' } },
  { empId: 12, empNumber: 'EMP-000012', firstName: 'EMILY', lastName: 'JOHNSON', departmentName: 'Finance', jobTitle: 'Analyst', annualSalary: '54000.00', stateCode: 'CA', bank: null },
  { empId: 21, empNumber: 'EMP-000021', firstName: 'JENNIFER', lastName: 'PARK', departmentName: 'Engineering', jobTitle: 'Manager', annualSalary: '96000.00', stateCode: 'CA', bank: { bankName: 'First National', routingLast4: '0021', accountLast4: '8804' } },
  { empId: 22, empNumber: 'EMP-000022', firstName: 'THOMAS', lastName: 'BAKER', departmentName: 'Engineering', jobTitle: 'Analyst', annualSalary: null, stateCode: 'CA', bank: null },
];

/** Frozen constants (contracts/p4-payroll/README.md item 4). */
export const BASE_PAY_ELEMENT_ID = 1;
export const FED_TAX_ELEMENT_ID = 100;
export const STATE_TAX_ELEMENT_ID = 101;
export const FICA_ELEMENT_ID = 102;
export const MEDICARE_ELEMENT_ID = 103;
export const ERROR_ELEMENT_ID = 0;

const STATE_RATES: Record<string, number> = { NY: 0.0685, CA: 0.093 };
const PERIOD_DIVISOR = { WEEKLY: 52, BIWEEKLY: 26, SEMIMONTHLY: 24, MONTHLY: 12 } as const;

let periods: PayPeriod[] = [];
let runs: PayrollRun[] = [];
let details: PayrollDetail[] = [];
let statuses = new Map<number, PayrollRunStatus>();
/** Status polls left before a CALCULATING run completes (simulates the Spring Batch job). */
let pollsRemaining = new Map<number, number>();
let nextRunId = 1003;
let nextDetailId = 50_000;

export const NOW = '2024-04-15T10:00:00Z';

export function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

export function money(n: number): Money {
  return (Math.round(n * 100) / 100).toFixed(2);
}

function cents(m: Money): number {
  return Math.round(Number(m) * 100);
}

export function sum(values: Money[]): Money {
  return (values.reduce((acc, v) => acc + cents(v), 0) / 100).toFixed(2);
}

export function employeeById(empId: number): PayrollEmployee | undefined {
  return PAYROLL_EMPLOYEES.find((e) => e.empId === empId);
}

function period(periodId: number, periodName: string, start: string, end: string, payDate: string, status: PayPeriod['status'], closed?: string): PayPeriod {
  return {
    periodId,
    periodName,
    payFrequency: 'MONTHLY',
    periodStartDate: start,
    periodEndDate: end,
    payDate,
    status,
    closedBy: closed ? 'james.richardson@company.com' : null,
    closedDate: closed ?? null,
    runCount: 0,
    latestRunId: null,
    latestRunStatus: null,
  };
}

/**
 * Same steps as `PKG_PAYROLL.calculate_employee_pay`, simplified rates: gross = salary / divisor,
 * federal 12 % flat, state per `STATE_RATES` (missing → `MISSING_TAX_RATE`), FICA 6.2 %, Medicare
 * 1.45 %. Taxes carry the negative stored sign.
 */
export function calculateEmployee(run: PayrollRun, p: PayPeriod, e: PayrollEmployee): PayrollDetail[] {
  const base = (over: Partial<PayrollDetail>): PayrollDetail => ({
    detailId: nextDetailId++,
    runId: run.runId,
    empId: e.empId,
    empNumber: e.empNumber,
    elementId: 0,
    elementCode: 'ERROR',
    elementType: 'ERROR',
    hoursWorked: null,
    rate: null,
    amount: '0.00',
    ytdAmount: null,
    status: 'CALCULATED',
    errorCode: null,
    errorMessage: null,
    ...over,
  });
  const errorRow = (errorCode: string, errorMessage: string) => [base({ status: 'ERROR', errorCode, errorMessage })];

  if (e.annualSalary === null) return errorRow('-20104', `No active salary record for employee ${e.empId}`);
  if (Number(e.annualSalary) <= 0) return errorRow('-20101', `Salary must be positive: ${e.annualSalary}`);
  const stateRate = e.stateCode ? STATE_RATES[e.stateCode] : undefined;
  if (e.stateCode && stateRate === undefined) return errorRow('MISSING_TAX_RATE', `No tax bracket for state ${e.stateCode} in ${p.periodEndDate.slice(0, 4)}`);

  const gross = Number(e.annualSalary) / PERIOD_DIVISOR[p.payFrequency];
  const rows = [
    base({ elementId: BASE_PAY_ELEMENT_ID, elementCode: 'BASE_PAY', elementType: 'EARNING', amount: money(gross) }),
    base({ elementId: FED_TAX_ELEMENT_ID, elementCode: 'FED_TAX', elementType: 'TAX', rate: '0.1200', amount: money(-gross * 0.12) }),
  ];
  if (e.stateCode && stateRate !== undefined) {
    rows.push(base({ elementId: STATE_TAX_ELEMENT_ID, elementCode: 'STATE_TAX', elementType: 'TAX', rate: stateRate.toFixed(4), amount: money(-gross * stateRate) }));
  }
  rows.push(
    base({ elementId: FICA_ELEMENT_ID, elementCode: 'FICA', elementType: 'TAX', rate: '0.0620', amount: money(-gross * 0.062) }),
    base({ elementId: MEDICARE_ELEMENT_ID, elementCode: 'MEDICARE', elementType: 'TAX', rate: '0.0145', amount: money(-gross * 0.0145) }),
  );
  return rows;
}

export function recomputeRunTotals(run: PayrollRun) {
  const rows = details.filter((d) => d.runId === run.runId);
  const calculated = rows.filter((d) => d.status === 'CALCULATED');
  const employees = new Set(rows.map((d) => d.empId));
  const errors = rows.filter((d) => d.status === 'ERROR');
  run.totalGross = sum(calculated.filter((d) => d.elementType === 'EARNING').map((d) => d.amount));
  const negatives = calculated.filter((d) => d.elementType !== 'EARNING').map((d) => d.amount);
  run.totalDeductions = money(Math.abs(Number(sum(negatives))));
  run.totalNet = sum(calculated.map((d) => d.amount));
  run.employeeCount = employees.size;
  run.errorCount = errors.length;
}

function refreshPeriod(p: PayPeriod) {
  const own = runs.filter((r) => r.periodId === p.periodId).sort((a, b) => b.runId - a.runId);
  p.runCount = own.length;
  p.latestRunId = own[0]?.runId ?? null;
  p.latestRunStatus = own[0]?.status ?? null;
}

export function getPeriod(periodId: number): PayPeriod | undefined {
  return periods.find((p) => p.periodId === periodId);
}

export function listPeriods(): PayPeriod[] {
  return periods;
}

export function getRun(runId: number): PayrollRun | undefined {
  return runs.find((r) => r.runId === runId);
}

export function runsForPeriod(periodId: number): PayrollRun[] {
  return runs.filter((r) => r.periodId === periodId).sort((a, b) => b.runId - a.runId);
}

export function detailsForRun(runId: number, status?: DetailStatus): PayrollDetail[] {
  return details
    .filter((d) => d.runId === runId && (status ? d.status === status : d.status !== 'REVERSED'))
    .sort((a, b) => a.empId - b.empId || a.elementId - b.elementId || a.detailId - b.detailId);
}

export function getStatus(runId: number): PayrollRunStatus {
  const run = getRun(runId)!;
  const s = statuses.get(runId);
  if (s) return s;
  return { runId, status: run.status, jobExecutionId: null, processed: run.employeeCount, total: run.employeeCount, errorCount: run.errorCount, startedAt: null, finishedAt: null, failureMessage: null };
}

export function insertRun(periodId: number, runType: RunType, createdBy: string): PayrollRun {
  const p = getPeriod(periodId)!;
  const run: PayrollRun = {
    runId: nextRunId++,
    periodId,
    runType,
    runDate: NOW,
    status: 'PENDING',
    totalGross: '0.00',
    totalDeductions: '0.00',
    totalNet: '0.00',
    totalEmployerCost: null,
    employeeCount: 0,
    errorCount: 0,
    engine: 'JAVA',
    submittedBy: null,
    submittedDate: null,
    approvedBy: null,
    approvedDate: null,
    createdBy,
    createdDate: NOW,
  };
  runs.push(run);
  p.status = 'PROCESSING';
  refreshPeriod(p);
  return run;
}

/** `202`: the run flips to CALCULATING now; `pollStatus` advances the simulated job. */
export function startCalculation(run: PayrollRun, polls = 2): PayrollRunStatus {
  details = details.filter((d) => d.runId !== run.runId);
  run.status = 'CALCULATING';
  run.employeeCount = 0;
  run.errorCount = 0;
  const s: PayrollRunStatus = { runId: run.runId, status: 'CALCULATING', jobExecutionId: 7000 + run.runId, processed: 0, total: null, errorCount: 0, startedAt: NOW, finishedAt: null, failureMessage: null };
  statuses.set(run.runId, s);
  pollsRemaining.set(run.runId, polls);
  refreshPeriod(getPeriod(run.periodId)!);
  return clone(s);
}

export function pollStatus(run: PayrollRun): PayrollRunStatus {
  const s = statuses.get(run.runId);
  if (!s || s.status !== 'CALCULATING') return clone(getStatus(run.runId));
  const left = (pollsRemaining.get(run.runId) ?? 1) - 1;
  pollsRemaining.set(run.runId, left);
  const p = getPeriod(run.periodId)!;
  const population = PAYROLL_EMPLOYEES;
  s.total = population.length;
  if (left > 0) {
    s.processed = Math.floor(population.length / 2);
    return clone(s);
  }
  for (const e of population) details.push(...calculateEmployee(run, p, e));
  recomputeRunTotals(run);
  run.status = run.errorCount === run.employeeCount ? 'ERROR' : 'CALCULATED';
  s.status = run.status;
  s.processed = run.employeeCount;
  s.errorCount = run.errorCount;
  s.finishedAt = NOW;
  refreshPeriod(p);
  return clone(s);
}

export function approveRun(run: PayrollRun, username: string) {
  run.status = 'APPROVED';
  run.approvedBy = username;
  run.approvedDate = NOW;
  run.submittedBy = username;
  run.submittedDate = NOW;
  statuses.delete(run.runId);
  refreshPeriod(getPeriod(run.periodId)!);
}

export function reverseRun(run: PayrollRun) {
  run.status = 'REVERSED';
  for (const d of details) if (d.runId === run.runId) d.status = 'REVERSED';
  statuses.delete(run.runId);
  const p = getPeriod(run.periodId)!;
  p.status = 'OPEN';
  refreshPeriod(p);
}

export function closePeriod(p: PayPeriod, username: string) {
  p.status = 'CLOSED';
  p.closedBy = username;
  p.closedDate = NOW;
}

function abs(m: Money): Money {
  return money(Math.abs(Number(m)));
}

export function buildPayslip(run: PayrollRun, empId: number): Payslip | { errorCode: string; errorMessage: string } | null {
  const rows = details.filter((d) => d.runId === run.runId && d.empId === empId && d.status !== 'REVERSED');
  if (rows.length === 0) return null;
  const err = rows.find((d) => d.status === 'ERROR');
  if (err && rows.length === 1) return { errorCode: err.errorCode!, errorMessage: err.errorMessage! };
  const e = employeeById(empId)!;
  const p = getPeriod(run.periodId)!;
  const lines = rows.filter((d) => d.status === 'CALCULATED');
  const by = (id: number) => abs(sum(lines.filter((d) => d.elementId === id).map((d) => d.amount)));
  const other = abs(sum(lines.filter((d) => d.elementType === 'DEDUCTION' || d.elementType === 'BENEFIT').map((d) => d.amount)));
  const taxes = abs(sum(lines.filter((d) => d.elementType === 'TAX').map((d) => d.amount)));

  // Reporting YTD (BUG-06): APPROVED/PAID runs of the same year up to and including this period.
  const year = p.periodStartDate.slice(0, 4);
  const ytdRuns = runs.filter((r) => {
    const rp = getPeriod(r.periodId)!;
    return (r.status === 'APPROVED' || r.status === 'PAID') && rp.periodStartDate.startsWith(year) && rp.periodEndDate <= p.periodEndDate;
  });
  const ytdRows = details.filter((d) => d.empId === empId && d.status === 'CALCULATED' && ytdRuns.some((r) => r.runId === d.runId));
  const ytdGross = sum(ytdRows.filter((d) => d.elementType === 'EARNING').map((d) => d.amount));
  const ytdTaxes = abs(sum(ytdRows.filter((d) => d.elementType === 'TAX').map((d) => d.amount)));
  const ytdDeductions = abs(sum(ytdRows.filter((d) => d.elementType === 'DEDUCTION' || d.elementType === 'BENEFIT').map((d) => d.amount)));

  return {
    runId: run.runId,
    empId,
    empNumber: e.empNumber,
    empName: `${e.firstName} ${e.lastName}`,
    departmentName: e.departmentName,
    jobTitle: e.jobTitle,
    periodName: p.periodName,
    periodStartDate: p.periodStartDate,
    periodEndDate: p.periodEndDate,
    payDate: p.payDate,
    runStatus: run.status,
    grossPay: sum(lines.filter((d) => d.elementType === 'EARNING').map((d) => d.amount)),
    federalTax: by(FED_TAX_ELEMENT_ID),
    stateTax: by(STATE_TAX_ELEMENT_ID),
    socialSecurity: by(FICA_ELEMENT_ID),
    medicare: by(MEDICARE_ELEMENT_ID),
    otherDeductions: other,
    totalDeductions: sum([taxes, other]),
    netPay: sum(lines.map((d) => d.amount)),
    ytdGross,
    ytdTaxes,
    ytdDeductions,
    ytdNet: sum(ytdRows.map((d) => d.amount)),
    lines: clone(lines),
  };
}

/** RFC 4180, CRLF, legacy ten-column layout (+ masked bank columns on request). */
export function buildRegister(run: PayrollRun, includeBank: boolean): string {
  const header = ['EMP_NUMBER', 'EMPLOYEE_NAME', 'DEPARTMENT', 'GROSS_PAY', 'FED_TAX', 'STATE_TAX', 'SS_TAX', 'MEDICARE', 'DEDUCTIONS', 'NET_PAY'];
  if (includeBank) header.push('BANK_NAME', 'ROUTING_LAST4', 'ACCOUNT_LAST4');
  const empIds = [...new Set(details.filter((d) => d.runId === run.runId && d.status === 'CALCULATED').map((d) => d.empId))];
  const people = empIds.map((id) => employeeById(id)!).sort((a, b) => a.lastName.localeCompare(b.lastName) || a.firstName.localeCompare(b.firstName) || a.empNumber.localeCompare(b.empNumber));
  const escape = (v: string) => (/[",\r\n]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v);
  const lines = [header.join(',')];
  for (const e of people) {
    const slip = buildPayslip(run, e.empId) as Payslip;
    const row = [e.empNumber, `${e.firstName} ${e.lastName}`, e.departmentName, slip.grossPay, slip.federalTax, slip.stateTax, slip.socialSecurity, slip.medicare, slip.otherDeductions, slip.netPay];
    if (includeBank) row.push(e.bank?.bankName ?? '', e.bank ? `****${e.bank.routingLast4}` : '', e.bank ? `****${e.bank.accountLast4}` : '');
    lines.push(row.map(escape).join(','));
  }
  return lines.join('\r\n') + '\r\n';
}

export function buildShadowDiff(run: PayrollRun): ShadowDiffReport | null {
  const rows = details.filter((d) => d.runId === run.runId);
  if (rows.length === 0) return null;
  const p = getPeriod(run.periodId)!;
  const employees = new Set(rows.map((d) => d.empId)).size;
  const errorRows = rows.filter((d) => d.status === 'ERROR').length;
  return {
    runId: run.runId,
    periodId: run.periodId,
    taxYear: Number(p.periodEndDate.slice(0, 4)),
    engineFlag: 'JAVA',
    legacySource: 'recorded',
    comparedAt: NOW,
    summary: { employees, matched: rows.length - errorRows, explained: 0, unexplained: 0, legacyOnly: 0, javaOnly: 0, errorRowsLegacy: errorRows, errorRowsJava: errorRows, netDeltaCents: 0 },
    lines: [],
  };
}

export function resetPayrollState() {
  periods = [
    period(202401, '2024-01 Monthly', '2024-01-01', '2024-01-31', '2024-02-05', 'CLOSED', '2024-02-06T09:00:00Z'),
    period(202402, '2024-02 Monthly', '2024-02-01', '2024-02-29', '2024-03-05', 'PROCESSING'),
    period(202403, '2024-03 Monthly', '2024-03-01', '2024-03-31', '2024-04-05', 'OPEN'),
    period(202404, '2024-04 Monthly', '2024-04-01', '2024-04-30', '2024-05-06', 'OPEN'),
  ];
  runs = [];
  details = [];
  statuses = new Map();
  pollsRemaining = new Map();
  nextRunId = 1001;
  nextDetailId = 50_000;

  // 202401: approved run of record (feeds YTD); 202402: calculated run with one ERROR row.
  for (const [periodId, approve] of [
    [202401, true],
    [202402, false],
  ] as const) {
    const run = insertRun(periodId, 'REGULAR', 'james.richardson@company.com');
    startCalculation(run, 1);
    pollStatus(run);
    if (approve) approveRun(run, 'james.richardson@company.com');
  }
  periods[0].status = 'CLOSED';
  for (const p of periods) refreshPeriod(p);
}

resetPayrollState();
