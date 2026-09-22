import { Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { ApprovalsTab } from './ApprovalsTab';
import { MyRequestsTab } from './MyRequestsTab';
import { SubmitRequestTab } from './SubmitRequestTab';
import { TeamCalendarTab } from './TeamCalendarTab';

const tabs = [
  { label: 'My Requests', path: '/leave' },
  { label: 'Submit Request', path: '/leave/submit' },
  { label: 'Approvals', path: '/leave/approvals' },
  { label: 'Team Calendar', path: '/leave/team-calendar' },
];

/** HRMS_LEAVE.fmb replacement (COMPONENT_MAPPING.md §5): one page, four tabs. */
export function LeavePage() {
  const location = useLocation();
  const navigate = useNavigate();
  return (
    <section className="leave-page">
      <h1>Leave</h1>
      <div className="tabs" role="tablist" aria-label="Leave">
        {tabs.map((tab) => {
          const selected = tab.path === '/leave' ? location.pathname === tab.path : location.pathname.startsWith(tab.path);
          return (
            <button key={tab.path} type="button" role="tab" aria-selected={selected} onClick={() => navigate(tab.path)}>
              {tab.label}
            </button>
          );
        })}
      </div>
      <Routes>
        <Route index element={<MyRequestsTab />} />
        <Route path="submit" element={<SubmitRequestTab />} />
        <Route path="approvals" element={<ApprovalsTab />} />
        <Route path="team-calendar" element={<TeamCalendarTab />} />
        <Route path="*" element={<MyRequestsTab />} />
      </Routes>
    </section>
  );
}
