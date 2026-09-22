import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { api } from '../../api/client';
import type { Dependent, DependentRequest, EmployeeDetail } from '../../api/types';
import { formatDate } from '../../app/format';
import { useToast } from '../../app/ToastContext';
import { useErrorHandler } from '../../app/useErrorHandler';
import { getDto } from '../../validation/schema';
import { Dialog } from './Dialog';
import { useEmployeeModule } from './EmployeeModuleContext';
import { enumValues, isRequired, parseForm, type FormValues } from './formUtils';
import { employeeKeys } from './queryKeys';
import { TextField } from './TextField';

const DTO = 'DependentRequest';
const FIELDS = Object.keys(getDto(DTO).fields);

function initial(d: Dependent | null): FormValues {
  return {
    firstName: d?.firstName ?? '',
    lastName: d?.lastName ?? '',
    relationship: d?.relationship ?? '',
    dateOfBirth: d?.dateOfBirth ?? '',
    ssn: '',
    benefitsEnrolled: d ? String(d.benefitsEnrolled) : 'false',
    active: d ? String(d.active) : 'true',
  };
}

/** `DEPENDENT` block → `POST`/`PUT /api/employees/{id}/dependents[/{dependentId}]`. */
export function DependentDialog({ employee, dependent, onClose, onDone }: { employee: EmployeeDetail; dependent: Dependent | null; onClose: () => void; onDone: () => void }) {
  const [form, setForm] = useState<FormValues>(() => initial(dependent));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const { handleError } = useErrorHandler();
  const { push } = useToast();
  const set = (k: string) => (v: string) => setForm((o) => ({ ...o, [k]: v }));

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const parsed = parseForm<DependentRequest & { active?: string }>(DTO, form);
    if (!parsed.ok) {
      setErrors(parsed.errors);
      return;
    }
    setErrors({});
    setBusy(true);
    try {
      const body: DependentRequest = { ...parsed.data, active: dependent ? form.active === 'true' : undefined };
      if (dependent) await api.employees.updateDependent(employee.id, dependent.dependentId, body);
      else await api.employees.addDependent(employee.id, body);
      push({ kind: 'success', message: dependent ? 'Dependent updated' : 'Dependent added' });
      onDone();
    } catch (error) {
      setErrors(handleError(error, { fieldNames: FIELDS }).fieldErrors);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog title={dependent ? 'Edit dependent' : 'Add dependent'} onSubmit={submit} onClose={onClose} busy={busy} submitLabel="Save">
      <TextField id="dep-firstName" label="First name" value={form.firstName} onChange={set('firstName')} required={isRequired(DTO, 'firstName')} error={errors.firstName} />
      <TextField id="dep-lastName" label="Last name" value={form.lastName} onChange={set('lastName')} required={isRequired(DTO, 'lastName')} error={errors.lastName} />
      <TextField id="dep-relationship" label="Relationship" value={form.relationship} onChange={set('relationship')} options={enumValues(DTO, 'relationship')} required={isRequired(DTO, 'relationship')} error={errors.relationship} />
      <TextField id="dep-dateOfBirth" label="Date of birth" type="date" value={form.dateOfBirth} onChange={set('dateOfBirth')} error={errors.dateOfBirth} />
      <TextField id="dep-ssn" label={dependent ? `SSN (on file: ${dependent.ssnLast4 ? `•••-••-${dependent.ssnLast4}` : 'none'}; leave blank to keep)` : 'SSN'} type="password" autoComplete="off" value={form.ssn} onChange={set('ssn')} error={errors.ssn} />
      <TextField id="dep-benefitsEnrolled" label="Benefits enrolled" value={form.benefitsEnrolled} onChange={set('benefitsEnrolled')} options={['true', 'false']} error={errors.benefitsEnrolled} />
      {dependent && <TextField id="dep-active" label="Active" value={form.active} onChange={set('active')} options={['true', 'false']} />}
    </Dialog>
  );
}

export function DependentsTab({ employee }: { employee: EmployeeDetail }) {
  const qc = useQueryClient();
  const { writable, canViewSubResources } = useEmployeeModule();
  const [editing, setEditing] = useState<Dependent | null | 'new'>(null);
  const scoped = canViewSubResources(employee.id);
  const list = useQuery({ queryKey: employeeKeys.dependents(employee.id), queryFn: () => api.employees.listDependents(employee.id), enabled: scoped });
  if (!scoped) return <p role="note">Dependents are visible to the employee and HR only.</p>;
  const canWrite = writable && employee.employmentStatus !== 'TERMINATED';
  const done = () => {
    setEditing(null);
    void qc.invalidateQueries({ queryKey: employeeKeys.dependents(employee.id) });
  };
  return (
    <section aria-labelledby="dependents-title">
      <div className="toolbar">
        <h3 id="dependents-title">Dependents</h3>
        {canWrite && (
          <button type="button" onClick={() => setEditing('new')}>
            Add dependent
          </button>
        )}
      </div>
      {list.isPending ? (
        <p role="status">Loading…</p>
      ) : list.isError ? (
        <p role="alert">Could not load dependents.</p>
      ) : list.data.length ? (
        <table className="grid" aria-label="Dependents">
          <thead>
            <tr>
              <th>Name</th>
              <th>Relationship</th>
              <th>Date of birth</th>
              <th>SSN</th>
              <th>Benefits</th>
              <th>Active</th>
              {canWrite && <th />}
            </tr>
          </thead>
          <tbody>
            {list.data.map((d) => (
              <tr key={d.dependentId}>
                <td>
                  {d.firstName} {d.lastName}
                </td>
                <td>{d.relationship.replace(/_/g, ' ')}</td>
                <td>{d.dateOfBirth ? formatDate(d.dateOfBirth) : '—'}</td>
                <td>{d.ssnLast4 ? `•••-••-${d.ssnLast4}` : '—'}</td>
                <td>{d.benefitsEnrolled ? 'Enrolled' : '—'}</td>
                <td>{d.active ? 'Yes' : 'No'}</td>
                {canWrite && (
                  <td>
                    <button type="button" onClick={() => setEditing(d)} aria-label={`Edit dependent ${d.firstName} ${d.lastName}`}>
                      Edit
                    </button>
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <p>No dependents recorded.</p>
      )}
      {editing !== null && <DependentDialog employee={employee} dependent={editing === 'new' ? null : editing} onClose={() => setEditing(null)} onDone={done} />}
    </section>
  );
}
