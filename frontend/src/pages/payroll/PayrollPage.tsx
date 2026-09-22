import { Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { useModuleFlag } from '../../app/ModuleFlagsContext';
import { PayDetailsTab } from './PayDetailsTab';
import { PayPeriodsTab } from './PayPeriodsTab';
import { PayrollRunsTab } from './PayrollRunsTab';
import { PayslipView } from './PayslipView';

/**
 * HRMS_PAYROLL.fmb replacement (COMPONENT_MAPPING.md §4): one page, three grids plus the payslip
 * view. The route is mounted only while the proxy reports `payroll=NEW` (which the proxy grants
 * only after `payroll.engine=JAVA` passes the shadow gate, CUTOVER_PLAN.md §8.4); at any other
 * flag the Home tile keeps routing to Forms and this page redirects home.
 */
export function PayrollPage() {
  const flag = useModuleFlag('payroll');
  const location = useLocation();
  const navigate = useNavigate();
  if (flag !== 'NEW') return <Navigate to="/" replace />;

  const runsMatch = /^\/payroll\/periods\/(\d+)\/runs/.exec(location.pathname);
  const runMatch = /^\/payroll\/runs\/(\d+)/.exec(location.pathname);
  const tabs = [
    { id: 'periods', label: 'Pay Periods', path: '/payroll', selected: location.pathname === '/payroll', enabled: true },
    { id: 'runs', label: 'Payroll Runs', path: runsMatch ? `/payroll/periods/${runsMatch[1]}/runs` : null, selected: Boolean(runsMatch), enabled: Boolean(runsMatch) },
    { id: 'details', label: 'Pay Details', path: runMatch ? `/payroll/runs/${runMatch[1]}/details` : null, selected: Boolean(runMatch), enabled: Boolean(runMatch) },
  ];

  return (
    <section className="payroll-page">
      <h1>Payroll</h1>
      <div className="tabs" role="tablist" aria-label="Payroll">
        {tabs.map((tab) => (
          <button
            key={tab.id}
            type="button"
            role="tab"
            aria-selected={tab.selected}
            disabled={!tab.enabled}
            title={tab.enabled ? undefined : tab.id === 'runs' ? 'Select a pay period first' : 'Select a payroll run first'}
            onClick={() => tab.path && navigate(tab.path)}
          >
            {tab.label}
          </button>
        ))}
      </div>
      <Routes>
        <Route index element={<PayPeriodsTab />} />
        <Route path="periods/:periodId/runs" element={<PayrollRunsTab />} />
        <Route path="runs/:runId/details" element={<PayDetailsTab />} />
        <Route path="runs/:runId/payslips/:empId" element={<PayslipView />} />
        <Route path="*" element={<Navigate to="/payroll" replace />} />
      </Routes>
    </section>
  );
}
