import { Navigate, NavLink, Route, Routes } from 'react-router-dom';
import { useAuth } from '../../app/AuthContext';
import { useModuleFlag } from '../../app/ModuleFlagsContext';
import { AuditLogTab } from './AuditLogTab';
import { LeaveJobsTab } from './LeaveJobsTab';
import { ReferenceDataTab } from './ReferenceDataTab';
import { RolesTab } from './RolesTab';
import { SystemParametersTab } from './SystemParametersTab';
import { UsersTab } from './UsersTab';
import { departmentsConfig, jobGradesConfig, jobTitlesConfig, leaveTypesConfig, locationsConfig, useAdminLookups } from './adminDefinitions';
import { TaxBracketsTab, holidaysConfig, payElementsConfig } from './payrollReferenceDefinitions';

const TABS = [
  { id: 'departments', label: 'Departments' },
  { id: 'job-grades', label: 'Job grades' },
  { id: 'job-titles', label: 'Job titles' },
  { id: 'locations', label: 'Locations' },
  { id: 'leave-types', label: 'Leave types' },
  { id: 'system-parameters', label: 'System parameters' },
  { id: 'holidays', label: 'Holidays' },
  { id: 'pay-elements', label: 'Pay elements' },
  { id: 'tax-brackets', label: 'Tax brackets' },
  { id: 'roles', label: 'Roles' },
  { id: 'users', label: 'Users' },
  { id: 'leave-jobs', label: 'Leave jobs' },
  { id: 'audit-log', label: 'Audit log' },
] as const;

/**
 * The HRMS_ADMIN form was never delivered in the Forms estate (COMPONENT_MAPPING.md §9); this page
 * is rebuilt from the frozen contract: reference-data CRUD (admin-module is the single owner of
 * DEPARTMENTS / JOB_GRADES / JOB_TITLES / LOCATIONS / LEAVE_TYPES / SYSTEM_PARAMETERS, plus the
 * §9.2 expansion HOLIDAYS / PAY_ELEMENTS / TAX_BRACKETS), role & user-account administration
 * (auth-owned, same `/api/admin` prefix), the leave batch triggers and the audit-log search. Mounted only while the proxy reports `reporting=NEW`.
 */
export function AdminPage() {
  const flag = useModuleFlag('reporting');
  const { hasAuthority } = useAuth();
  const lookups = useAdminLookups();
  if (flag !== 'NEW') return <Navigate to="/" replace />;

  const canView = hasAuthority('ADMIN:VIEW');
  const canRunJobs = hasAuthority('LEAVE:ADMIN');
  const visible = TABS.filter((t) => (t.id === 'leave-jobs' ? canRunJobs || canView : canView));
  if (visible.length === 0) return <Navigate to="/" replace />;

  return (
    <section className="admin-page">
      <h1>Administration</h1>
      <nav className="tabs" aria-label="Administration">
        {visible.map((t) => (
          <NavLink key={t.id} to={`/admin/${t.id}`} role="tab" className={({ isActive }) => (isActive ? 'active' : undefined)}>
            {t.label}
          </NavLink>
        ))}
      </nav>
      <Routes>
        <Route index element={<Navigate to={`/admin/${visible[0].id}`} replace />} />
        {canView && <Route path="departments" element={<ReferenceDataTab config={departmentsConfig(lookups)} />} />}
        {canView && <Route path="job-grades" element={<ReferenceDataTab config={jobGradesConfig()} />} />}
        {canView && <Route path="job-titles" element={<ReferenceDataTab config={jobTitlesConfig(lookups)} />} />}
        {canView && <Route path="locations" element={<ReferenceDataTab config={locationsConfig()} />} />}
        {canView && <Route path="leave-types" element={<ReferenceDataTab config={leaveTypesConfig()} />} />}
        {canView && <Route path="system-parameters" element={<SystemParametersTab />} />}
        {canView && <Route path="holidays" element={<ReferenceDataTab config={holidaysConfig(lookups)} />} />}
        {canView && <Route path="pay-elements" element={<ReferenceDataTab config={payElementsConfig()} />} />}
        {canView && <Route path="tax-brackets" element={<TaxBracketsTab />} />}
        {canView && <Route path="roles" element={<RolesTab />} />}
        {canView && <Route path="users" element={<UsersTab />} />}
        <Route path="leave-jobs" element={<LeaveJobsTab />} />
        {canView && <Route path="audit-log" element={<AuditLogTab />} />}
        <Route path="*" element={<Navigate to="/admin" replace />} />
      </Routes>
    </section>
  );
}
