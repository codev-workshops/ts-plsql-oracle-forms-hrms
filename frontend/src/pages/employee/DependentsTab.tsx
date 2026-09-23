import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { api } from '../../api/client';
import type { Dependent, DependentRequest, EmployeeDetail, Relationship } from '../../api/types';
import { formatDate } from '../../app/format';
import { useToast } from '../../app/ToastContext';
import { useErrorHandler } from '../../app/useErrorHandler';
import { fieldErrors as zodFieldErrors, getDto, zodFor } from '../../validation/schema';
import { RELATIONSHIPS, compact, employeeDependentsKey, humanize, useEmployeeWrite } from './employeeShared';

const schema = zodFor('DependentRequest');
const FIELDS = ['firstName', 'lastName', 'relationship', 'dateOfBirth', 'ssn', 'benefitsEnrolled', 'active'] as const;

interface Values {
  firstName: string;
  lastName: string;
  relationship: Relationship | '';
  dateOfBirth: string;
  ssn: string;
  benefitsEnrolled: boolean;
  active: boolean;
}

const EMPTY: Values = { firstName: '', lastName: '', relationship: '', dateOfBirth: '', ssn: '', benefitsEnrolled: false, active: true };

function fromRow(d: Dependent): Values {
  return {
    firstName: d.firstName,
    lastName: d.lastName,
    relationship: d.relationship,
    dateOfBirth: d.dateOfBirth ?? '',
    ssn: '',
    benefitsEnrolled: d.benefitsEnrolled,
    active: d.active,
  };
}

/** `DEPENDENT` block (HRMS_EMPLOYEE.fmb "Dependents" tab) → `/api/employees/{id}/dependents`. */
export function DependentsTab({ employee }: { employee: EmployeeDetail }) {
  const qc = useQueryClient();
  const { canEditRelated } = useEmployeeWrite();
  const editable = canEditRelated(employee.id) && employee.employmentStatus !== 'TERMINATED';
  const [editing, setEditing] = useState<Dependent | 'new' | null>(null);
  const dependents = useQuery({ queryKey: employeeDependentsKey(employee.id), queryFn: () => api.employees.listDependents(employee.id) });
  const saved = () => {
    setEditing(null);
    void qc.invalidateQueries({ queryKey: employeeDependentsKey(employee.id) });
  };

  return (
    <section aria-labelledby="dependents-title">
      <div className="toolbar">
        <h3 id="dependents-title">Dependents</h3>
        {editable && editing === null && <button type="button" onClick={() => setEditing('new')}>Add dependent</button>}
      </div>
      {dependents.isPending ? (
        <p role="status">Loading…</p>
      ) : dependents.isError ? (
        <p role="alert">Could not load dependents.</p>
      ) : dependents.data.length ? (
        <table className="grid" aria-label="Dependents">
          <thead>
            <tr><th>Name</th><th>Relationship</th><th>Date of birth</th><th>SSN</th><th>Benefits</th><th>Active</th>{editable && <th />}</tr>
          </thead>
          <tbody>
            {dependents.data.map((d) => (
              <tr key={d.dependentId}>
                <td>{d.lastName}, {d.firstName}</td>
                <td>{humanize(d.relationship)}</td>
                <td>{d.dateOfBirth ? formatDate(d.dateOfBirth) : '—'}</td>
                <td>{d.ssnLast4 ? `•••-••-${d.ssnLast4}` : '—'}</td>
                <td>{d.benefitsEnrolled ? 'Enrolled' : 'No'}</td>
                <td>{d.active ? 'Yes' : 'No'}</td>
                {editable && (
                  <td>
                    <button type="button" onClick={() => setEditing(d)} aria-label={`Edit dependent ${d.firstName} ${d.lastName}`}>Edit</button>
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <p>No dependents recorded.</p>
      )}
      {editing !== null && editable && (
        <DependentForm empId={employee.id} dependent={editing === 'new' ? null : editing} onCancel={() => setEditing(null)} onSaved={saved} />
      )}
    </section>
  );
}

function DependentForm({ empId, dependent, onCancel, onSaved }: { empId: number; dependent: Dependent | null; onCancel: () => void; onSaved: () => void }) {
  const [values, setValues] = useState<Values>(dependent ? fromRow(dependent) : EMPTY);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const { push } = useToast();
  const { handleError } = useErrorHandler();
  const set = <K extends keyof Values>(k: K, v: Values[K]) => setValues((s) => ({ ...s, [k]: v }));

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const candidate = { ...compact({ firstName: values.firstName, lastName: values.lastName, relationship: values.relationship, dateOfBirth: values.dateOfBirth, ssn: values.ssn }, 'DependentRequest'), benefitsEnrolled: values.benefitsEnrolled, active: values.active };
    const parsed = schema.safeParse(candidate);
    if (!parsed.success) {
      setErrors(zodFieldErrors(parsed.error));
      return;
    }
    setErrors({});
    setBusy(true);
    try {
      const body = candidate as unknown as DependentRequest;
      if (dependent) await api.employees.updateDependent(empId, dependent.dependentId, body);
      else await api.employees.addDependent(empId, body);
      push({ kind: 'success', message: dependent ? 'Dependent updated' : 'Dependent added' });
      onSaved();
    } catch (error) {
      setErrors(handleError(error, { fieldNames: FIELDS }).fieldErrors);
    } finally {
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} noValidate aria-label={dependent ? 'Edit dependent' : 'Add dependent'} className="inline-form">
      <div className="form-grid">
        <div className="field">
          <label htmlFor="dep-firstName">First name <span aria-hidden="true">*</span></label>
          <input id="dep-firstName" value={values.firstName} maxLength={50} onChange={(e) => set('firstName', e.target.value)} aria-invalid={errors.firstName ? true : undefined} />
          {errors.firstName && <span role="alert" className="field-error">{errors.firstName}</span>}
        </div>
        <div className="field">
          <label htmlFor="dep-lastName">Last name <span aria-hidden="true">*</span></label>
          <input id="dep-lastName" value={values.lastName} maxLength={50} onChange={(e) => set('lastName', e.target.value)} aria-invalid={errors.lastName ? true : undefined} />
          {errors.lastName && <span role="alert" className="field-error">{errors.lastName}</span>}
        </div>
        <div className="field">
          <label htmlFor="dep-relationship">Relationship <span aria-hidden="true">*</span></label>
          <select id="dep-relationship" value={values.relationship} onChange={(e) => set('relationship', e.target.value as Relationship | '')} aria-invalid={errors.relationship ? true : undefined}>
            <option value="">— Select —</option>
            {RELATIONSHIPS.map((r) => <option key={r} value={r}>{humanize(r)}</option>)}
          </select>
          {errors.relationship && <span role="alert" className="field-error">{errors.relationship}</span>}
        </div>
        <div className="field">
          <label htmlFor="dep-dateOfBirth">Date of birth</label>
          <input id="dep-dateOfBirth" type="date" value={values.dateOfBirth} onChange={(e) => set('dateOfBirth', e.target.value)} aria-invalid={errors.dateOfBirth ? true : undefined} />
          {errors.dateOfBirth && <span role="alert" className="field-error">{errors.dateOfBirth}</span>}
        </div>
        <div className="field">
          <label htmlFor="dep-ssn">{dependent ? 'New SSN (leave blank to keep)' : 'SSN'}</label>
          <input id="dep-ssn" type={getDto('DependentRequest').fields.ssn.sensitive ? 'password' : 'text'} value={values.ssn} maxLength={11} autoComplete="off" placeholder="NNN-NN-NNNN" onChange={(e) => set('ssn', e.target.value)} aria-invalid={errors.ssn ? true : undefined} />
          {errors.ssn && <span role="alert" className="field-error">{errors.ssn}</span>}
        </div>
        <fieldset className="field">
          <label><input type="checkbox" checked={values.benefitsEnrolled} onChange={(e) => set('benefitsEnrolled', e.target.checked)} /> Benefits enrolled</label>
          <label><input type="checkbox" checked={values.active} onChange={(e) => set('active', e.target.checked)} /> Active</label>
        </fieldset>
      </div>
      <div className="actions">
        <button type="submit" disabled={busy}>{dependent ? 'Save dependent' : 'Add dependent'}</button>
        <button type="button" onClick={onCancel} disabled={busy}>Cancel</button>
      </div>
    </form>
  );
}
