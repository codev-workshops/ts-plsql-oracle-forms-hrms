import { Navigate, Route, Routes } from 'react-router-dom';
import { LoginPage } from '../auth/LoginPage';
import { AuditPage } from '../features/audit';
import { EmployeePage } from '../features/employee';
import { LeavePage } from '../features/leave';
import { PayrollPage } from '../features/payroll';
import { PerformancePage } from '../features/performance';
import { ReportingPage } from '../features/reporting';
import { Layout } from './Layout';
import { RequireAuth } from './RequireAuth';

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        element={
          <RequireAuth>
            <Layout />
          </RequireAuth>
        }
      >
        <Route path="/leave/*" element={<LeavePage />} />
        <Route path="/employees/*" element={<EmployeePage />} />
        <Route path="/payroll/*" element={<PayrollPage />} />
        <Route path="/performance/*" element={<PerformancePage />} />
        <Route path="/audit/*" element={<AuditPage />} />
        <Route path="/reports/*" element={<ReportingPage />} />
        <Route path="*" element={<Navigate to="/leave" replace />} />
      </Route>
    </Routes>
  );
}
