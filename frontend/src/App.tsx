import { Navigate, Route, Routes } from 'react-router-dom';
import { useAuth } from './auth/AuthContext';
import { Layout } from './components/Layout';
import { ChangePassword } from './pages/ChangePassword';
import { EmployeeDetail, Employees } from './pages/Employees';
import { Leave } from './pages/Leave';
import { Login } from './pages/Login';
import { Payroll } from './pages/Payroll';
import { Performance } from './pages/Performance';
import { Reports } from './pages/Reports';

export function App() {
  const { user, loading } = useAuth();
  if (loading) return <p>Loading…</p>;
  if (!user) return <Login />;
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<Navigate to="/employees" replace />} />
        <Route path="/employees" element={<Employees />} />
        <Route path="/employees/:empId" element={<EmployeeDetail />} />
        <Route path="/leave" element={<Leave />} />
        <Route path="/performance" element={<Performance />} />
        <Route path="/payroll" element={<Payroll />} />
        <Route path="/reports" element={<Reports />} />
        <Route path="/account/password" element={<ChangePassword />} />
        <Route path="*" element={<p>Not found</p>} />
      </Route>
    </Routes>
  );
}
