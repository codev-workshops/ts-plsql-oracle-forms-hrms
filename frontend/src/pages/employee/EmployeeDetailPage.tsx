import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { api } from '../../api/client';
import type { EmployeeDetail, EmployeeDetailWithEtag } from '../../api/types';
import { ContactsTab } from './ContactsTab';
import { DependentsTab } from './DependentsTab';
import { EmployeeForm } from './EmployeeForm';
import { EmployeeStatusBadge } from './EmployeeStatusBadge';
import { HistoryTab } from './HistoryTab';
import { SalaryChangeDialog } from './SalaryChangeDialog';
import { SalaryTab } from './SalaryTab';
import { TerminateDialog } from './TerminateDialog';
import { TransferDialog } from './TransferDialog';
import { employeeDetailKey, useEmployeeWrite } from './employeeShared';

const TABS = [
  { label: 'Details', segment: '' },
  { label: 'History', segment: 'history' },
  { label: 'Salary', segment: 'salary' },
  { label: 'Dependents', segment: 'dependents' },
  { label: 'Contacts', segment: 'contacts' },
];

type Dialog = 'terminate' | 'transfer' | 'salary' | null;

/** One employee record: `EMPLOYEE` block + tab canvas of HRMS_EMPLOYEE.fmb (COMPONENT_MAPPING.md §3). */
export function EmployeeDetailPage({ empId }: { empId: number }) {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const location = useLocation();
  const { canEditEmployee, canChangeSalary } = useEmployeeWrite();
  const [dialog, setDialog] = useState<Dialog>(null);
  const detail = useQuery({ queryKey: employeeDetailKey(empId), queryFn: () => api.employees.getEmployee(empId) });

  const base = `/employees/${empId}`;
  const applyDetail = (employee: EmployeeDetail, etag?: string) => {
    qc.setQueryData<EmployeeDetailWithEtag>(employeeDetailKey(empId), { employee, etag: etag ?? `"${employee.version}"` });
    void qc.invalidateQueries({ queryKey: ['employees'] });
    void qc.invalidateQueries({ queryKey: ['salary'] });
    setDialog(null);
  };

  if (detail.isPending) return <p role="status">Loading…</p>;
  if (detail.isError) {
    return (
      <div>
        <p role="alert">Could not load employee {empId}.</p>
        <Link to="/employees">Back to search</Link>
      </div>
    );
  }
  const { employee, etag } = detail.data;
  const active = employee.employmentStatus === 'ACTIVE';

  return (
    <section aria-labelledby="employee-detail-title">
      <div className="toolbar employee-header">
        <Link to="/employees">← Search</Link>
        <h2 id="employee-detail-title">
          {employee.lastName}, {employee.firstName} <small>{employee.empNumber}</small>
        </h2>
        <EmployeeStatusBadge status={employee.employmentStatus} />
        <span className="actions">
          {canEditEmployee && active && (
            <>
              <button type="button" onClick={() => setDialog('transfer')}>Transfer</button>
              <button type="button" onClick={() => setDialog('terminate')}>Terminate</button>
            </>
          )}
          {canChangeSalary && active && <button type="button" onClick={() => setDialog('salary')}>Change salary</button>}
        </span>
      </div>

      <div className="tabs" role="tablist" aria-label="Employee record">
        {TABS.map((tab) => {
          const path = tab.segment ? `${base}/${tab.segment}` : base;
          const selected = tab.segment ? location.pathname.startsWith(path) : location.pathname === base;
          return (
            <button key={tab.label} type="button" role="tab" aria-selected={selected} onClick={() => navigate(path)}>
              {tab.label}
            </button>
          );
        })}
      </div>

      <Routes>
        <Route index element={<EmployeeForm key={`${employee.id}:${employee.version}`} mode="edit" employee={employee} etag={etag} onSaved={applyDetail} />} />
        <Route path="history" element={<HistoryTab empId={empId} />} />
        <Route path="salary" element={<SalaryTab employee={employee} onChangeSalary={canChangeSalary && active ? () => setDialog('salary') : undefined} />} />
        <Route path="dependents" element={<DependentsTab employee={employee} />} />
        <Route path="contacts" element={<ContactsTab employee={employee} />} />
        <Route path="*" element={<EmployeeForm key={`${employee.id}:${employee.version}`} mode="edit" employee={employee} etag={etag} onSaved={applyDetail} />} />
      </Routes>

      {dialog === 'terminate' && <TerminateDialog employee={employee} onClose={() => setDialog(null)} onTerminated={applyDetail} />}
      {dialog === 'transfer' && <TransferDialog employee={employee} onClose={() => setDialog(null)} onTransferred={applyDetail} />}
      {dialog === 'salary' && <SalaryChangeDialog employee={employee} onClose={() => setDialog(null)} onChanged={() => applyDetail(employee, etag)} />}
    </section>
  );
}
