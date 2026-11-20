import { useState } from 'react';
import { api, call, type Schemas } from '../api/client';
import { DataTable, ErrorBanner, useLoad, type Column } from '../components/ui';

type ReportId = 'PENDING_APPROVALS' | 'LEAVE_SUMMARY' | 'ORG_HIERARCHY' | 'EMPLOYEE_COMPENSATION' | 'PAYROLL_LATEST';

const LOADERS: Record<ReportId, () => Promise<unknown[]>> = {
  PENDING_APPROVALS: () => call(api.GET('/api/v1/reports/pending-approvals')),
  LEAVE_SUMMARY: () => call(api.GET('/api/v1/reports/leave-summary')),
  ORG_HIERARCHY: () => call(api.GET('/api/v1/reports/org-hierarchy')),
  EMPLOYEE_COMPENSATION: () => call(api.GET('/api/v1/reports/employee-compensation')),
  PAYROLL_LATEST: () => call(api.GET('/api/v1/reports/payroll-latest')),
};

const COLUMNS: Record<ReportId, Column<never>[]> = {
  PENDING_APPROVALS: [
    { header: 'Type', render: (r: Schemas['PendingApproval']) => r.approvalType },
    { header: 'Requestor', render: (r: Schemas['PendingApproval']) => r.requestorName },
    { header: 'Item', render: (r: Schemas['PendingApproval']) => r.itemDescription },
    { header: 'Details', render: (r: Schemas['PendingApproval']) => r.details ?? '' },
    { header: 'Approver', render: (r: Schemas['PendingApproval']) => r.approverId ?? '' },
  ],
  LEAVE_SUMMARY: [
    { header: 'Employee', render: (r: Schemas['LeaveSummaryRow']) => `${r.empNumber} ${r.empName}` },
    { header: 'Department', render: (r: Schemas['LeaveSummaryRow']) => r.deptName },
    { header: 'Leave type', render: (r: Schemas['LeaveSummaryRow']) => r.leaveTypeName },
    { header: 'Used', render: (r: Schemas['LeaveSummaryRow']) => r.used },
    { header: 'Pending', render: (r: Schemas['LeaveSummaryRow']) => r.pending },
    { header: 'Available (net of pending)', render: (r: Schemas['LeaveSummaryRow']) => r.available },
    { header: 'Available before pending (legacy)', render: (r: Schemas['LeaveSummaryRow']) => r.availableBeforePending },
    { header: 'Utilization %', render: (r: Schemas['LeaveSummaryRow']) => r.utilizationPct ?? '' },
  ],
  ORG_HIERARCHY: [
    { header: 'Level', render: (r: Schemas['OrgNode']) => r.orgLevel },
    { header: 'Employee', render: (r: Schemas['OrgNode']) => `${'— '.repeat(r.orgLevel - 1)}${r.empName}` },
    { header: 'Path', render: (r: Schemas['OrgNode']) => r.orgPath },
  ],
  EMPLOYEE_COMPENSATION: [
    { header: 'Employee', render: (r: Schemas['EmployeeCompensation']) => `${r.empNumber} ${r.empName}` },
    { header: 'Department', render: (r: Schemas['EmployeeCompensation']) => r.deptName },
    { header: 'Grade', render: (r: Schemas['EmployeeCompensation']) => r.gradeName },
    { header: 'Base salary', render: (r: Schemas['EmployeeCompensation']) => r.baseSalary },
    { header: 'Compa-ratio', render: (r: Schemas['EmployeeCompensation']) => r.compaRatio ?? '' },
  ],
  PAYROLL_LATEST: [
    { header: 'Employee', render: (r: Schemas['PayrollLatest']) => `${r.empNumber} ${r.empName}` },
    { header: 'Period', render: (r: Schemas['PayrollLatest']) => r.periodName },
    { header: 'Gross', render: (r: Schemas['PayrollLatest']) => r.grossPay },
    { header: 'Net', render: (r: Schemas['PayrollLatest']) => r.netPay },
  ],
} as unknown as Record<ReportId, Column<never>[]>;

/** Reports & Analytics (OQ-03): the catalogue from GET /api/v1/reports; unavailable reports are disabled. */
export function Reports() {
  const catalogue = useLoad(() => call(api.GET('/api/v1/reports')), []);
  const [active, setActive] = useState<ReportId | null>(null);
  const rows = useLoad(() => (active ? LOADERS[active]() : Promise.resolve(null)), [active]);
  return (
    <section>
      <h2>Reports &amp; Analytics</h2>
      <ErrorBanner error={catalogue.error} />
      <div className="tabs">
        {catalogue.data?.map((r) => (
          <button key={r.id} type="button" disabled={!r.available} aria-pressed={active === r.id} onClick={() => setActive(r.id as ReportId)}>
            {r.title}
          </button>
        ))}
      </div>
      <ErrorBanner error={rows.error} />
      {active && <DataTable label={active} rows={rows.data as never[] | null} columns={COLUMNS[active]} />}
    </section>
  );
}
