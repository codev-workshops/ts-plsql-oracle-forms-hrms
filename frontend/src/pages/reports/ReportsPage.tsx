import { Navigate, NavLink, Route, Routes } from 'react-router-dom';
import { useAuth } from '../../app/AuthContext';
import { useModuleFlag } from '../../app/ModuleFlagsContext';
import { ReportView } from './ReportView';
import {
  REPORT_ENTRIES,
  employeeCompensationReport,
  employeeDirectoryReport,
  leaveSummaryReport,
  orgHierarchyReport,
  payrollLatestReport,
  pendingApprovalsReport,
  useReferenceOptions,
} from './reportDefinitions';

/**
 * HRMS_REPORTS.fmb replacement (COMPONENT_MAPPING.md §9): six `VW_*`-backed reports, each with
 * the frozen filter set and a CSV twin. Mounted only while the proxy reports `reporting=NEW`.
 * Compensation and Latest Payroll additionally need `PAYROLL:VIEW`; Pending Approvals is reachable
 * for any signed-in approver with `mine=true` (contracts/p5-reporting-decommission/README.md).
 * Each `ReportView` is keyed by report id: the sibling routes would otherwise share one component
 * instance (filter state, page and the query's placeholder data) when switching tabs.
 */
export function ReportsPage() {
  const flag = useModuleFlag('reporting');
  const { hasAuthority } = useAuth();
  const ref = useReferenceOptions();
  if (flag !== 'NEW') return <Navigate to="/" replace />;

  const canSeeReports = hasAuthority('REPORTS:VIEW');
  const allowed = (id: string) => {
    const entry = REPORT_ENTRIES.find((e) => e.id === id);
    if (!entry) return false;
    if (id === 'pending-approvals') return true;
    return entry.authorities.every(hasAuthority);
  };
  const visible = REPORT_ENTRIES.filter((e) => allowed(e.id));
  const first = visible[0]?.path ?? '/';

  return (
    <section className="reports-page">
      <h1>Reports</h1>
      <nav className="tabs" aria-label="Reports">
        {visible.map((e) => (
          <NavLink key={e.id} to={e.path} role="tab" className={({ isActive }) => (isActive ? 'active' : undefined)}>
            {e.label}
          </NavLink>
        ))}
      </nav>
      <Routes>
        <Route index element={<Navigate to={first} replace />} />
        {allowed('employee-directory') && <Route path="employee-directory" element={<ReportView key="employee-directory" report={employeeDirectoryReport(ref)} />} />}
        {allowed('org-hierarchy') && <Route path="org-hierarchy" element={<ReportView key="org-hierarchy" report={orgHierarchyReport()} />} />}
        {allowed('employee-compensation') && <Route path="employee-compensation" element={<ReportView key="employee-compensation" report={employeeCompensationReport(ref)} />} />}
        {allowed('leave-summary') && <Route path="leave-summary" element={<ReportView key="leave-summary" report={leaveSummaryReport(ref)} />} />}
        {allowed('payroll-latest') && <Route path="payroll-latest" element={<ReportView key="payroll-latest" report={payrollLatestReport(ref)} />} />}
        <Route path="pending-approvals" element={<ReportView key="pending-approvals" report={pendingApprovalsReport(ref)} defaults={canSeeReports ? undefined : { mine: true }} />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </section>
  );
}
