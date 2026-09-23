import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/client';
import type {
  EmployeeCompensationPage,
  EmployeeCompensationQuery,
  EmployeeCompensationRow,
  EmployeeDirectoryPage,
  EmployeeDirectoryQuery,
  EmployeeDirectoryRow,
  LeaveSummaryPage,
  LeaveSummaryQuery,
  LeaveSummaryRow,
  OrgHierarchyPage,
  OrgHierarchyQuery,
  OrgHierarchyRow,
  PayrollLatestPage,
  PayrollLatestQuery,
  PayrollLatestRow,
  PendingApprovalPage,
  PendingApprovalRow,
  PendingApprovalsQuery,
} from '../../api/types';
import type { Authority } from '../../api/types';
import { referenceQueryKey } from '../../components/ReferenceDropdown';
import { humanizeCode, type FieldOption } from '../shared/schemaForm';
import type { ReportDefinition } from './ReportView';

/** Lookup options for the filter selects come from the P0 read-only `reference-module` (admin-module is the owner). */
export function useReferenceOptions() {
  const departments = useQuery({ queryKey: referenceQueryKey('departments', { active: true }), queryFn: () => api.reference.listDepartments({ active: true }) });
  const locations = useQuery({ queryKey: referenceQueryKey('locations', { active: true }), queryFn: () => api.reference.listLocations({ active: true }) });
  const leaveTypes = useQuery({ queryKey: referenceQueryKey('leave-types', { active: true }), queryFn: () => api.reference.listLeaveTypes({ active: true }) });
  const jobTitles = useQuery({ queryKey: referenceQueryKey('job-titles', { active: true }), queryFn: () => api.reference.listJobTitles({ active: true }) });
  const grades = new Map<number, string>();
  for (const j of jobTitles.data ?? []) grades.set(j.gradeId, `${j.gradeCode} – ${j.gradeName}`);
  return {
    departments: (departments.data ?? []).map((d): FieldOption => ({ value: String(d.deptId), label: `${d.deptCode} – ${d.deptName}` })),
    locations: (locations.data ?? []).map((l): FieldOption => ({ value: l.locationCode, label: l.locationName })),
    leaveTypes: (leaveTypes.data ?? []).map((t): FieldOption => ({ value: String(t.leaveTypeId), label: t.leaveTypeName })),
    grades: [...grades.entries()].sort((a, b) => a[0] - b[0]).map(([id, label]): FieldOption => ({ value: String(id), label })),
  };
}

export type ReferenceOptions = ReturnType<typeof useReferenceOptions>;

export interface ReportEntry {
  id: string;
  label: string;
  path: string;
  /** All of these are required (`x-preauthorize … and …`). */
  authorities: Authority[];
}

export const REPORT_ENTRIES: ReportEntry[] = [
  { id: 'employee-directory', label: 'Employee Directory', path: '/reports/employee-directory', authorities: ['REPORTS:VIEW'] },
  { id: 'org-hierarchy', label: 'Org Hierarchy', path: '/reports/org-hierarchy', authorities: ['REPORTS:VIEW'] },
  { id: 'employee-compensation', label: 'Compensation', path: '/reports/employee-compensation', authorities: ['REPORTS:VIEW', 'PAYROLL:VIEW'] },
  { id: 'leave-summary', label: 'Leave Summary', path: '/reports/leave-summary', authorities: ['REPORTS:VIEW'] },
  { id: 'payroll-latest', label: 'Latest Payroll', path: '/reports/payroll-latest', authorities: ['REPORTS:VIEW', 'PAYROLL:VIEW'] },
  { id: 'pending-approvals', label: 'Pending Approvals', path: '/reports/pending-approvals', authorities: ['REPORTS:VIEW'] },
];

const dash = (v: string | number | null | undefined) => (v === null || v === undefined || v === '' ? '—' : v);

export function employeeDirectoryReport(ref: ReferenceOptions): ReportDefinition<EmployeeDirectoryQuery, EmployeeDirectoryRow, EmployeeDirectoryPage> {
  return {
    id: 'employee-directory',
    title: 'Employee Directory',
    legacy: 'VW_EMPLOYEE_DIRECTORY / RPT_EMP_DIRECTORY',
    dto: 'EmployeeDirectoryQuery',
    filters: [
      { name: 'asOf', label: 'As of' },
      { name: 'deptId', label: 'Department', options: ref.departments },
      { name: 'locationCode', label: 'Location', options: ref.locations },
    ],
    fetch: api.reports.employeeDirectory,
    csv: api.reports.employeeDirectoryCsv,
    rowKey: (r) => String(r.empId),
    columns: [
      { key: 'empNumber', label: 'Emp #', render: (r) => r.empNumber },
      { key: 'fullName', label: 'Name', render: (r) => r.fullName },
      { key: 'deptName', label: 'Department', render: (r) => `${r.deptCode} – ${r.deptName}` },
      { key: 'jobTitle', label: 'Job title', render: (r) => r.jobTitle },
      { key: 'gradeCode', label: 'Grade', render: (r) => r.gradeCode },
      { key: 'locationName', label: 'Location', render: (r) => dash(r.locationName) },
      { key: 'managerName', label: 'Manager', render: (r) => dash(r.managerName) },
      { key: 'hireDate', label: 'Hire date', render: (r) => r.hireDate },
      { key: 'tenureYears', label: 'Tenure (y)', render: (r) => r.tenureYears, numeric: true },
      { key: 'email', label: 'E-mail', render: (r) => dash(r.email) },
    ],
    summary: (p) => (
      <p data-testid="directory-summary">
        As of {p.asOf}: <strong>{p.summary.totalHeadcount}</strong> employees ·{' '}
        {p.summary.headcountByDepartment.map((d) => `${d.deptCode} ${d.headcount} (avg ${d.avgTenureYears}y)`).join(' · ')}
      </p>
    ),
  };
}

export function orgHierarchyReport(): ReportDefinition<OrgHierarchyQuery, OrgHierarchyRow, OrgHierarchyPage> {
  return {
    id: 'org-hierarchy',
    title: 'Organization Hierarchy',
    legacy: 'VW_ORG_HIERARCHY / RPT_ORG_CHART',
    dto: 'OrgHierarchyQuery',
    filters: [
      { name: 'asOf', label: 'As of' },
      { name: 'rootEmpId', label: 'Root employee id', placeholder: 'Whole organization' },
      { name: 'maxLevel', label: 'Max level' },
    ],
    fetch: api.reports.orgHierarchy,
    csv: api.reports.orgHierarchyCsv,
    rowKey: (r) => `${r.orgPath}#${r.empId}`,
    columns: [
      { key: 'orgLevel', label: 'Level', render: (r) => r.orgLevel, numeric: true },
      { key: 'fullName', label: 'Name', render: (r) => <span style={{ paddingLeft: `${(r.orgLevel - 1) * 1.25}rem` }}>{r.fullName}</span> },
      { key: 'empNumber', label: 'Emp #', render: (r) => r.empNumber },
      { key: 'jobTitle', label: 'Job title', render: (r) => r.jobTitle },
      { key: 'deptName', label: 'Department', render: (r) => r.deptName },
      { key: 'managerName', label: 'Manager', render: (r) => dash(r.managerName) },
      { key: 'directReports', label: 'Direct reports', render: (r) => dash(r.directReports), numeric: true },
      { key: 'orgPath', label: 'Path', render: (r) => r.orgPath },
      { key: 'cycle', label: 'Cycle', render: (r) => (r.cycle ? <span className="badge badge-REJECTED">cycle</span> : '—') },
    ],
  };
}

export function employeeCompensationReport(ref: ReferenceOptions): ReportDefinition<EmployeeCompensationQuery, EmployeeCompensationRow, EmployeeCompensationPage> {
  return {
    id: 'employee-compensation',
    title: 'Employee Compensation',
    legacy: 'VW_EMPLOYEE_COMPENSATION / RPT_COMPENSATION',
    dto: 'EmployeeCompensationQuery',
    filters: [
      { name: 'asOf', label: 'As of' },
      { name: 'deptId', label: 'Department', options: ref.departments },
      { name: 'gradeId', label: 'Grade', options: ref.grades },
    ],
    fetch: api.reports.employeeCompensation,
    csv: api.reports.employeeCompensationCsv,
    rowKey: (r) => String(r.empId),
    columns: [
      { key: 'empNumber', label: 'Emp #', render: (r) => r.empNumber },
      { key: 'fullName', label: 'Name', render: (r) => r.fullName },
      { key: 'deptName', label: 'Department', render: (r) => r.deptName },
      { key: 'jobTitle', label: 'Job title', render: (r) => r.jobTitle },
      { key: 'gradeCode', label: 'Grade', render: (r) => r.gradeCode },
      { key: 'baseSalary', label: 'Base salary', render: (r) => `${r.baseSalary} ${r.currencyCode}`, numeric: true },
      { key: 'payFrequency', label: 'Frequency', render: (r) => humanizeCode(r.payFrequency) },
      { key: 'effectiveDate', label: 'Effective', render: (r) => r.effectiveDate },
      { key: 'range', label: 'Grade range', render: (r) => `${r.minSalary} – ${r.maxSalary}`, numeric: true },
      { key: 'compaRatio', label: 'Compa-ratio', render: (r) => r.compaRatio, numeric: true },
      { key: 'yearsInGrade', label: 'Years in grade', render: (r) => r.yearsInGrade, numeric: true },
    ],
    summary: (p) => (
      <table className="grid grid-compact" aria-label="Compensation by department" data-testid="compensation-summary">
        <thead>
          <tr>
            <th scope="col">Department</th>
            <th scope="col" className="num">Headcount</th>
            <th scope="col" className="num">Average</th>
            <th scope="col" className="num">Min</th>
            <th scope="col" className="num">Max</th>
            <th scope="col" className="num">Total payroll</th>
          </tr>
        </thead>
        <tbody>
          {p.summary.byDepartment.map((d) => (
            <tr key={d.deptId}>
              <td>{d.deptName}</td>
              <td className="num">{d.headcount}</td>
              <td className="num">{d.avgSalary}</td>
              <td className="num">{d.minSalary}</td>
              <td className="num">{d.maxSalary}</td>
              <td className="num">{d.totalPayroll}</td>
            </tr>
          ))}
        </tbody>
      </table>
    ),
  };
}

export function leaveSummaryReport(ref: ReferenceOptions): ReportDefinition<LeaveSummaryQuery, LeaveSummaryRow, LeaveSummaryPage> {
  return {
    id: 'leave-summary',
    title: 'Leave Summary',
    legacy: 'VW_LEAVE_SUMMARY / RPT_LEAVE_BALANCES (available = opening + accrued − used + adjustment − pending, VAL-05)',
    dto: 'LeaveSummaryQuery',
    filters: [
      { name: 'asOf', label: 'As of' },
      { name: 'year', label: 'Year' },
      { name: 'deptId', label: 'Department', options: ref.departments },
      { name: 'leaveTypeId', label: 'Leave type', options: ref.leaveTypes },
    ],
    fetch: api.reports.leaveSummary,
    csv: api.reports.leaveSummaryCsv,
    rowKey: (r) => `${r.empId}-${r.leaveTypeId}-${r.calendarYear}`,
    columns: [
      { key: 'empNumber', label: 'Emp #', render: (r) => r.empNumber },
      { key: 'empName', label: 'Name', render: (r) => r.empName },
      { key: 'deptName', label: 'Department', render: (r) => r.deptName },
      { key: 'leaveTypeName', label: 'Leave type', render: (r) => r.leaveTypeName },
      { key: 'calendarYear', label: 'Year', render: (r) => r.calendarYear, numeric: true },
      { key: 'openingBalance', label: 'Opening', render: (r) => r.openingBalance, numeric: true },
      { key: 'accrued', label: 'Accrued', render: (r) => r.accrued, numeric: true },
      { key: 'used', label: 'Used', render: (r) => r.used, numeric: true },
      { key: 'adjustment', label: 'Adjustment', render: (r) => r.adjustment, numeric: true },
      { key: 'pending', label: 'Pending', render: (r) => r.pending, numeric: true },
      { key: 'available', label: 'Available', render: (r) => <strong>{r.available}</strong>, numeric: true },
      { key: 'legacyAvailable', label: 'Legacy available', render: (r) => <span title="VW_LEAVE_SUMMARY figure (pending not deducted) – reconciliation only">{r.legacyAvailable}</span>, numeric: true },
      { key: 'utilizationPct', label: 'Utilization %', render: (r) => dash(r.utilizationPct), numeric: true },
    ],
    summary: (p) => (
      <p data-testid="leave-summary-totals">
        {p.year}:{' '}
        {p.summary.byLeaveType.map((t) => `${t.leaveTypeName} – ${t.employees} employees, accrued ${t.totalAccrued}, used ${t.totalUsed}${t.avgUtilizationPct === null ? '' : ` (${t.avgUtilizationPct}%)`}`).join(' · ')}
      </p>
    ),
  };
}

export function payrollLatestReport(ref: ReferenceOptions): ReportDefinition<PayrollLatestQuery, PayrollLatestRow, PayrollLatestPage> {
  return {
    id: 'payroll-latest',
    title: 'Latest Payroll',
    legacy: 'VW_PAYROLL_SUMMARY_LATEST / RPT_PAYROLL_SUMMARY',
    dto: 'PayrollLatestQuery',
    filters: [
      { name: 'periodId', label: 'Period id', placeholder: 'Latest approved/paid' },
      { name: 'deptId', label: 'Department', options: ref.departments },
    ],
    fetch: api.reports.payrollLatest,
    csv: api.reports.payrollLatestCsv,
    rowKey: (r) => `${r.runId}-${r.empId}`,
    columns: [
      { key: 'empNumber', label: 'Emp #', render: (r) => r.empNumber },
      { key: 'empName', label: 'Name', render: (r) => r.empName },
      { key: 'deptName', label: 'Department', render: (r) => r.deptName },
      { key: 'periodName', label: 'Period', render: (r) => r.periodName },
      { key: 'payDate', label: 'Pay date', render: (r) => r.payDate },
      { key: 'run', label: 'Run', render: (r) => `${r.runId} · ${humanizeCode(r.runType)} · ${humanizeCode(r.runStatus)}` },
      { key: 'grossPay', label: 'Gross', render: (r) => r.grossPay, numeric: true },
      { key: 'totalTaxes', label: 'Taxes', render: (r) => r.totalTaxes, numeric: true },
      { key: 'totalDeductions', label: 'Deductions', render: (r) => r.totalDeductions, numeric: true },
      { key: 'netPay', label: 'Net', render: (r) => <strong>{r.netPay}</strong>, numeric: true },
    ],
    summary: (p) => (
      <p data-testid="payroll-latest-summary">
        Period {p.summary.periodId}: {p.summary.employeeCount} employees · gross {p.summary.totalGross} · taxes {p.summary.totalTaxes} · deductions {p.summary.totalDeductions} · net{' '}
        <strong>{p.summary.totalNet}</strong> (avg {p.summary.avgNet})
      </p>
    ),
  };
}

export function pendingApprovalsReport(ref: ReferenceOptions): ReportDefinition<PendingApprovalsQuery, PendingApprovalRow, PendingApprovalPage> {
  return {
    id: 'pending-approvals',
    title: 'Pending Approvals',
    legacy: 'VW_PENDING_APPROVALS / RPT_PENDING_APPROVALS',
    dto: 'PendingApprovalsQuery',
    filters: [
      { name: 'asOf', label: 'As of' },
      { name: 'itemType', label: 'Item type' },
      { name: 'deptId', label: 'Department', options: ref.departments },
      { name: 'mine', label: 'Only items awaiting me', hint: 'Uses the signed-in approver (JWT), never an employee id' },
    ],
    fetch: api.reports.pendingApprovals,
    csv: api.reports.pendingApprovalsCsv,
    rowKey: (r) => `${r.itemType}-${r.itemId}`,
    columns: [
      { key: 'itemType', label: 'Type', render: (r) => <span className={`badge badge-${r.itemType}`}>{humanizeCode(r.itemType)}</span> },
      { key: 'itemId', label: 'Item', render: (r) => r.itemId, numeric: true },
      { key: 'empName', label: 'Employee', render: (r) => `${r.empName}${r.empNumber ? ` (${r.empNumber})` : ''}` },
      { key: 'deptName', label: 'Department', render: (r) => r.deptName },
      { key: 'approverName', label: 'Approver', render: (r) => dash(r.approverName) },
      { key: 'submittedDate', label: 'Submitted', render: (r) => r.submittedDate },
      { key: 'daysPending', label: 'Days pending', render: (r) => r.daysPending, numeric: true },
      { key: 'detail', label: 'Detail', render: (r) => r.detail },
    ],
    summary: (p) => (
      <p data-testid="pending-summary">
        As of {p.asOf}: <strong>{p.summary.leave}</strong> leave · <strong>{p.summary.review}</strong> review
      </p>
    ),
  };
}
