import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, NavLink, Navigate, Route, Routes, useNavigate, useParams } from 'react-router-dom';
import { api } from '../../api/client';
import type { EmployeeDetail } from '../../api/types';
import { formatDate } from '../../app/format';
import { normaliseError } from '../../app/useErrorHandler';
import { ContactsTab } from './ContactsTab';
import { DependentsTab } from './DependentsTab';
import { EmployeeForm } from './EmployeeForm';
import { useEmployeeModule } from './EmployeeModuleContext';
import { HistoryTab } from './HistoryTab';
import { employeeKeys } from './queryKeys';
import { SalaryTab } from './SalaryTab';
import { StatusBadge } from './StatusBadge';
import { TerminateDialog } from './TerminateDialog';
import { TransferDialog } from './TransferDialog';

const TABS = [
  { path: '', label: 'Details' },
  { path: 'history', label: 'History' },
  { path: 'salary', label: 'Salary' },
  { path: 'dependents', label: 'Dependents' },
  { path: 'contacts', label: 'Contacts' },
] as const;

function Row({ label, value }: { label: string; value: string | number | null | undefined }) {
  return (
    <>
      <dt>{label}</dt>
      <dd>{value === null || value === undefined || value === '' ? '—' : value}</dd>
    </>
  );
}

/** Read view of the `EMPLOYEE` block; SSN is only ever the server-masked last 4. */
export function EmployeeDetails({ employee: e }: { employee: EmployeeDetail }) {
  return (
    <dl className="detail-grid" aria-label="Employee details">
      <Row label="Employee number" value={e.empNumber} />
      <Row label="Name" value={[e.firstName, e.middleName, e.lastName].filter(Boolean).join(' ')} />
      <Row label="Date of birth" value={e.dateOfBirth ? formatDate(e.dateOfBirth) : null} />
      <Row label="Gender" value={e.gender} />
      <Row label="Marital status" value={e.maritalStatus} />
      <Row label="Nationality" value={e.nationality} />
      <Row label="SSN" value={e.ssnLast4 ? `•••-••-${e.ssnLast4}` : null} />
      <Row label="E-mail" value={e.email} />
      <Row label="Work phone" value={e.phoneWork} />
      <Row label="Mobile phone" value={e.phoneMobile} />
      <Row label="Address" value={[e.addressLine1, e.addressLine2, e.city, e.stateProvince, e.postalCode, e.countryCode].filter(Boolean).join(', ')} />
      <Row label="Hire date" value={formatDate(e.hireDate)} />
      <Row label="Department" value={e.deptName} />
      <Row label="Job title" value={e.gradeCode ? `${e.jobTitle} (${e.gradeCode})` : e.jobTitle} />
      <Row label="Manager" value={e.managerName} />
      <Row label="Location" value={e.locationName ?? e.locationCode} />
      <Row label="Employment type" value={e.employmentType.replace(/_/g, ' ')} />
      {e.terminationDate && <Row label="Terminated" value={`${formatDate(e.terminationDate)} · ${e.terminationReason ?? ''}`} />}
      <Row label="Notes" value={e.notes} />
      <Row label="Last modified" value={e.modifiedDate ? `${formatDate(e.modifiedDate.slice(0, 10))} by ${e.modifiedBy ?? '—'}` : `Created ${formatDate(e.createdDate.slice(0, 10))} by ${e.createdBy}`} />
    </dl>
  );
}

/**
 * HRMS_EMPLOYEE.fmb detail canvas (COMPONENT_MAPPING.md §3): header + Details/History/Salary/
 * Dependents/Contacts tabs. Write controls appear only at `employee=NEW` with `EMPLOYEE:EDIT`.
 */
export function EmployeeDetailPage() {
  const { id } = useParams();
  const empId = Number(id);
  const navigate = useNavigate();
  const { writable, canEdit } = useEmployeeModule();
  const [editing, setEditing] = useState(false);
  const [dialog, setDialog] = useState<'terminate' | 'transfer' | null>(null);
  const detail = useQuery({ queryKey: employeeKeys.detail(empId), queryFn: () => api.employees.getEmployee(empId), enabled: Number.isInteger(empId) && empId > 0, retry: false });

  if (!Number.isInteger(empId) || empId <= 0) return <Navigate to="/employees" replace />;
  if (detail.isPending) return <p role="status">Loading…</p>;
  if (detail.isError) {
    const { apiError } = normaliseError(detail.error);
    return (
      <section>
        <p role="alert">{apiError?.code === '-20001' ? 'Employee not found.' : 'Could not load employee.'}</p>
        <Link to="/employees">Back to employees</Link>
      </section>
    );
  }
  const e = detail.data;
  const isActive = e.employmentStatus === 'ACTIVE';
  const showWrites = writable && canEdit;

  return (
    <section aria-labelledby="employee-title">
      <p>
        <Link to="/employees">← Employees</Link>
      </p>
      <div className="toolbar">
        <h2 id="employee-title">
          {e.firstName} {e.lastName} <small>{e.empNumber}</small> <StatusBadge status={e.employmentStatus} />
        </h2>
        {showWrites && !editing && (
          <div className="actions">
            {e.employmentStatus !== 'TERMINATED' && (
              <button type="button" onClick={() => setEditing(true)}>
                Edit
              </button>
            )}
            {isActive && (
              <button type="button" onClick={() => setDialog('transfer')}>
                Transfer
              </button>
            )}
            {e.employmentStatus !== 'TERMINATED' && (
              <button type="button" className="danger" onClick={() => setDialog('terminate')}>
                Terminate
              </button>
            )}
          </div>
        )}
      </div>
      {!writable && <p role="note" className="banner banner-info">Employee records are read-only during cutover.</p>}

      <nav className="tabs" aria-label="Employee sections">
        {TABS.map((t) => (
          <NavLink key={t.path} to={t.path === '' ? `/employees/${e.id}` : `/employees/${e.id}/${t.path}`} end={t.path === ''} role="tab" className={({ isActive: on }) => (on ? 'tab active' : 'tab')}>
            {t.label}
          </NavLink>
        ))}
      </nav>

      <Routes>
        <Route
          index
          element={
            editing ? (
              <EmployeeForm
                mode={{ kind: 'edit', employee: e }}
                onSaved={() => setEditing(false)}
                onCancel={() => setEditing(false)}
              />
            ) : (
              <EmployeeDetails employee={e} />
            )
          }
        />
        <Route path="history" element={<HistoryTab empId={e.id} />} />
        <Route path="salary" element={<SalaryTab employee={e} />} />
        <Route path="dependents" element={<DependentsTab employee={e} />} />
        <Route path="contacts" element={<ContactsTab employee={e} />} />
        <Route path="*" element={<Navigate to={`/employees/${e.id}`} replace />} />
      </Routes>

      {dialog === 'terminate' && (
        <TerminateDialog
          employee={e}
          onClose={() => setDialog(null)}
          onDone={() => {
            setDialog(null);
            navigate(`/employees/${e.id}/history`);
          }}
        />
      )}
      {dialog === 'transfer' && (
        <TransferDialog
          employee={e}
          onClose={() => setDialog(null)}
          onDone={() => {
            setDialog(null);
            navigate(`/employees/${e.id}/history`);
          }}
        />
      )}
    </section>
  );
}
