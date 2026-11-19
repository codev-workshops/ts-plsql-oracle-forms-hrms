import { Navigate, NavLink, Route, Routes, useParams } from 'react-router-dom';
import { useAuth } from '../../auth/AuthContext';
import { PayrollOverview } from './PayrollOverview';
import { PayrollRunPage } from './PayrollRunPage';
import { PayslipPage } from './PayslipPage';
import { positiveId } from './format';
import './payroll.css';

function RunRoute({ canEdit }: { canEdit: boolean }) {
  const { runId } = useParams();
  const id = positiveId(runId ?? null);
  return id ? <PayrollRunPage key={id} runId={id} canEdit={canEdit} /> : <Navigate to="/payroll" replace />;
}

export function PayrollPage() {
  const { user } = useAuth();
  if (!user) return null;
  const canViewAll = user.gradeLevel >= 5;
  const canEdit = user.gradeLevel >= 8;
  const payslipPage = <PayslipPage callerId={user.empId} canViewAll={canViewAll} />;
  return (
    <>
      <h1>Payroll</h1>
      {canViewAll && (
        <nav className="payroll-nav" aria-label="Payroll">
          <NavLink to="/payroll" end>Periods and runs</NavLink>
          <NavLink to="/payroll/payslips">Payslips</NavLink>
        </nav>
      )}
      <Routes>
        <Route index element={canViewAll ? <PayrollOverview canEdit={canEdit} /> : payslipPage} />
        {canViewAll && <Route path="runs/:runId" element={<RunRoute canEdit={canEdit} />} />}
        <Route path="payslips" element={payslipPage} />
        <Route path="*" element={<Navigate to="/payroll" replace />} />
      </Routes>
    </>
  );
}
