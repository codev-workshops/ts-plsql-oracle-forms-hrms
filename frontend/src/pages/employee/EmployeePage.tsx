import { Navigate, Route, Routes, useParams } from 'react-router-dom';
import { EmployeeDetailPage } from './EmployeeDetailPage';
import { EmployeeGrid } from './EmployeeGrid';
import { EmployeeForm } from './EmployeeForm';
import { useEmployeeWrite } from './employeeShared';

function CreateRoute() {
  const { canEditEmployee } = useEmployeeWrite();
  if (!canEditEmployee) return <Navigate to="/employees" replace />;
  return (
    <section aria-labelledby="new-employee-title">
      <h2 id="new-employee-title">New employee</h2>
      <EmployeeForm mode="create" />
    </section>
  );
}

function DetailRoute() {
  const { id } = useParams();
  const empId = Number(id);
  if (!Number.isInteger(empId) || empId <= 0) return <Navigate to="/employees" replace />;
  return <EmployeeDetailPage empId={empId} />;
}

/**
 * HRMS_EMPLOYEE.fmb replacement (COMPONENT_MAPPING.md §3): search grid → employee record with
 * Details / History / Salary / Dependents / Contacts tabs. Write flows exist but render only
 * while the proxy reports `employee=NEW` (CUTOVER_PLAN.md §7.1).
 */
export function EmployeePage() {
  const { readOnly } = useEmployeeWrite();
  return (
    <section className="employee-page">
      <h1>Employees</h1>
      {readOnly && (
        <p role="status" className="banner banner-info" data-testid="read-only-banner">
          Employee records are read-only while the module is being cut over.
        </p>
      )}
      <Routes>
        <Route index element={<EmployeeGrid />} />
        <Route path="new" element={<CreateRoute />} />
        <Route path=":id/*" element={<DetailRoute />} />
        <Route path="*" element={<Navigate to="/employees" replace />} />
      </Routes>
    </section>
  );
}
