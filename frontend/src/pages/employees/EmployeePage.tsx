import { Navigate, Route, Routes, useNavigate } from 'react-router-dom';
import type { ModuleFlagValue } from '../../app/modules';
import { EmployeeDetailPage } from './EmployeeDetailPage';
import { EmployeeForm } from './EmployeeForm';
import { EmployeeGrid } from './EmployeeGrid';
import { EmployeeModuleProvider, useEmployeeModule } from './EmployeeModuleContext';

function NewEmployee() {
  const navigate = useNavigate();
  const { writable, canEdit } = useEmployeeModule();
  if (!writable || !canEdit) return <Navigate to="/employees" replace />;
  return (
    <section aria-labelledby="new-employee-title">
      <h2 id="new-employee-title">New employee</h2>
      <EmployeeForm mode={{ kind: 'create' }} onSaved={(e) => navigate(`/employees/${e.id}`)} onCancel={() => navigate('/employees')} />
    </section>
  );
}

/**
 * HRMS_EMPLOYEE.fmb → `/employees/*` (COMPONENT_MAPPING.md §3). Mounted by AppShell once the
 * proxy reports `employee` promoted; `flags` is injectable for tests.
 */
export function EmployeePage({ flags }: { flags: Record<string, ModuleFlagValue> }) {
  return (
    <EmployeeModuleProvider flags={flags}>
      <Routes>
        <Route index element={<EmployeeGrid />} />
        <Route path="new" element={<NewEmployee />} />
        <Route path=":id/*" element={<EmployeeDetailPage />} />
        <Route path="*" element={<Navigate to="/employees" replace />} />
      </Routes>
    </EmployeeModuleProvider>
  );
}
