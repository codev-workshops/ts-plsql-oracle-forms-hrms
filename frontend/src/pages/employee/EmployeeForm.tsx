import { useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/client';
import type { EmployeeCreateRequest, EmployeeDetail, EmployeeUpdateRequest, EmploymentType, Gender } from '../../api/types';
import { formatDate, formatDateTime } from '../../app/format';
import { useToast } from '../../app/ToastContext';
import { useErrorHandler } from '../../app/useErrorHandler';
import { ReferenceDropdown } from '../../components/ReferenceDropdown';
import { fieldErrors as zodFieldErrors, getDto, zodFor } from '../../validation/schema';
import { EMPLOYMENT_TYPES, GENDERS, compact, employeeDetailKey, humanize, toMoney, useEmployeeWrite } from './employeeShared';

const createSchema = zodFor('EmployeeCreateRequest');
const updateSchema = zodFor('EmployeeUpdateRequest');
const CREATE_FIELDS = Object.keys(getDto('EmployeeCreateRequest').fields);

interface FormValues {
  firstName: string;
  middleName: string;
  lastName: string;
  dateOfBirth: string;
  gender: Gender | '';
  maritalStatus: string;
  nationality: string;
  ssn: string;
  email: string;
  phoneWork: string;
  phoneMobile: string;
  addressLine1: string;
  addressLine2: string;
  city: string;
  stateProvince: string;
  postalCode: string;
  countryCode: string;
  hireDate: string;
  deptId: string | null;
  jobId: string | null;
  managerEmpId: string | null;
  locationCode: string | null;
  employmentType: EmploymentType | '';
  initialSalary: string;
  notes: string;
}

const EMPTY: FormValues = {
  firstName: '', middleName: '', lastName: '', dateOfBirth: '', gender: '', maritalStatus: '', nationality: '', ssn: '',
  email: '', phoneWork: '', phoneMobile: '', addressLine1: '', addressLine2: '', city: '', stateProvince: '', postalCode: '',
  countryCode: '', hireDate: '', deptId: null, jobId: null, managerEmpId: null, locationCode: null, employmentType: 'FULL_TIME',
  initialSalary: '', notes: '',
};

function fromDetail(e: EmployeeDetail): FormValues {
  return {
    ...EMPTY,
    firstName: e.firstName,
    middleName: e.middleName ?? '',
    lastName: e.lastName,
    dateOfBirth: e.dateOfBirth ?? '',
    gender: e.gender ?? '',
    maritalStatus: e.maritalStatus ?? '',
    nationality: e.nationality ?? '',
    email: e.email ?? '',
    phoneWork: e.phoneWork ?? '',
    phoneMobile: e.phoneMobile ?? '',
    addressLine1: e.addressLine1 ?? '',
    addressLine2: e.addressLine2 ?? '',
    city: e.city ?? '',
    stateProvince: e.stateProvince ?? '',
    postalCode: e.postalCode ?? '',
    countryCode: e.countryCode ?? '',
    hireDate: e.hireDate,
    deptId: String(e.deptId),
    jobId: String(e.jobId),
    managerEmpId: e.managerEmpId === null ? null : String(e.managerEmpId),
    locationCode: e.locationCode,
    employmentType: e.employmentType,
    notes: e.notes ?? '',
  };
}

const PERSONAL = {
  firstName: 'First name', middleName: 'Middle name', lastName: 'Last name', maritalStatus: 'Marital status', nationality: 'Nationality',
  email: 'E-mail', phoneWork: 'Work phone', phoneMobile: 'Mobile phone', addressLine1: 'Address line 1', addressLine2: 'Address line 2',
  city: 'City', stateProvince: 'State / province', postalCode: 'Postal code', countryCode: 'Country code',
} as const;

const UPDATE_FIELDS: (keyof EmployeeUpdateRequest)[] = [
  'firstName', 'middleName', 'lastName', 'dateOfBirth', 'gender', 'maritalStatus', 'nationality', 'ssn', 'email', 'phoneWork', 'phoneMobile',
  'addressLine1', 'addressLine2', 'city', 'stateProvince', 'postalCode', 'countryCode', 'jobId', 'managerEmpId', 'employmentType', 'notes',
];

type Props =
  | { mode: 'create' }
  | { mode: 'edit'; employee: EmployeeDetail; etag: string; onSaved: (employee: EmployeeDetail, etag: string) => void };

/**
 * `EMPLOYEE` block of HRMS_EMPLOYEE.fmb (COMPONENT_MAPPING.md §3): create → `POST /api/employees`,
 * edit → `PUT /api/employees/{id}` with `If-Match`. Employee number / status / termination /
 * hire date / department are server-owned here (transfer & terminate have their own dialogs).
 */
export function EmployeeForm(props: Props) {
  const isEdit = props.mode === 'edit';
  const employee = isEdit ? props.employee : null;
  const { canEditEmployee } = useEmployeeWrite();
  const editable = canEditEmployee && (!employee || employee.employmentStatus !== 'TERMINATED');
  const [values, setValues] = useState<FormValues>(() => (employee ? fromDetail(employee) : EMPTY));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { push } = useToast();
  const { handleError } = useErrorHandler();

  const set = <K extends keyof FormValues>(key: K, value: FormValues[K]) => setValues((v) => ({ ...v, [key]: value }));

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const shared = compact({
      firstName: values.firstName,
      middleName: values.middleName,
      lastName: values.lastName,
      dateOfBirth: values.dateOfBirth,
      gender: values.gender,
      maritalStatus: values.maritalStatus,
      nationality: values.nationality,
      ssn: values.ssn,
      email: values.email,
      phoneWork: values.phoneWork,
      phoneMobile: values.phoneMobile,
      addressLine1: values.addressLine1,
      addressLine2: values.addressLine2,
      city: values.city,
      stateProvince: values.stateProvince,
      postalCode: values.postalCode,
      countryCode: values.countryCode,
      jobId: values.jobId ? Number(values.jobId) : undefined,
      managerEmpId: values.managerEmpId ? Number(values.managerEmpId) : undefined,
      employmentType: values.employmentType,
      notes: values.notes,
    }, props.mode === 'create' ? 'EmployeeCreateRequest' : 'EmployeeUpdateRequest');
    setBusy(true);
    try {
      if (props.mode === 'create') {
        const candidate = compact({
          ...shared,
          hireDate: values.hireDate,
          deptId: values.deptId ? Number(values.deptId) : undefined,
          locationCode: values.locationCode,
          initialSalary: values.initialSalary,
        }, 'EmployeeCreateRequest');
        const parsed = createSchema.safeParse(candidate);
        if (!parsed.success) {
          setErrors(zodFieldErrors(parsed.error));
          return;
        }
        setErrors({});
        const body = { ...candidate, ...(values.initialSalary ? { initialSalary: toMoney(values.initialSalary) } : {}) } as unknown as EmployeeCreateRequest;
        const created = await api.employees.createEmployee(body);
        push({ kind: 'success', message: `Employee ${created.empNumber} created` });
        navigate(`/employees/${created.id}`);
      } else {
        const parsed = updateSchema.safeParse(shared);
        if (!parsed.success) {
          setErrors(zodFieldErrors(parsed.error));
          return;
        }
        setErrors({});
        const result = await api.employees.updateEmployee(props.employee.id, props.etag, shared as unknown as EmployeeUpdateRequest);
        qc.setQueryData(employeeDetailKey(props.employee.id), result);
        void qc.invalidateQueries({ queryKey: ['employees'] });
        push({ kind: 'success', message: 'Employee saved' });
        setValues(fromDetail(result.employee));
        props.onSaved(result.employee, result.etag);
      }
    } catch (error) {
      const handled = handleError(error, { fieldNames: isEdit ? UPDATE_FIELDS : CREATE_FIELDS });
      setErrors(handled.fieldErrors);
    } finally {
      setBusy(false);
    }
  };

  const text = (name: keyof typeof PERSONAL, extra: { type?: string; maxLength?: number } = {}) => (
    <Field key={name} id={`emp-${name}`} label={PERSONAL[name]} error={errors[name]} required={name === 'firstName' || name === 'lastName'}>
      <input
        id={`emp-${name}`}
        type={extra.type ?? 'text'}
        value={values[name]}
        maxLength={extra.maxLength}
        onChange={(e) => set(name, e.target.value)}
        disabled={!editable}
        aria-invalid={errors[name] ? true : undefined}
      />
    </Field>
  );

  return (
    <form onSubmit={submit} noValidate aria-label={isEdit ? 'Employee details' : 'New employee'} className="employee-form">
      {employee && (
        <fieldset className="readonly-fields">
          <legend>Record</legend>
          <dl className="record-summary">
            <dt>Employee number</dt><dd data-testid="emp-number">{employee.empNumber}</dd>
            <dt>Status</dt><dd data-testid="emp-status">{humanize(employee.employmentStatus)}</dd>
            <dt>Hire date</dt><dd>{formatDate(employee.hireDate)}</dd>
            <dt>Department</dt><dd>{employee.deptName}</dd>
            <dt>Grade</dt><dd>{employee.gradeCode ?? '—'}</dd>
            {employee.terminationDate && (
              <>
                <dt>Terminated</dt><dd>{formatDate(employee.terminationDate)} · {employee.terminationReason}</dd>
              </>
            )}
            <dt>SSN</dt><dd>{employee.ssnLast4 ? `•••-••-${employee.ssnLast4}` : '—'}</dd>
            <dt>Version</dt><dd>{employee.version}</dd>
            <dt>Last modified</dt><dd>{employee.modifiedDate ? `${formatDateTime(employee.modifiedDate)} by ${employee.modifiedBy}` : '—'}</dd>
          </dl>
        </fieldset>
      )}

      <fieldset>
        <legend>Personal</legend>
        <div className="form-grid">
          {text('firstName', { maxLength: 50 })}
          {text('middleName', { maxLength: 50 })}
          {text('lastName', { maxLength: 50 })}
          <Field id="emp-dateOfBirth" label="Date of birth" error={errors.dateOfBirth}>
            <input id="emp-dateOfBirth" type="date" value={values.dateOfBirth} onChange={(e) => set('dateOfBirth', e.target.value)} disabled={!editable} aria-invalid={errors.dateOfBirth ? true : undefined} />
          </Field>
          <Field id="emp-gender" label="Gender" error={errors.gender}>
            <select id="emp-gender" value={values.gender} onChange={(e) => set('gender', e.target.value as Gender | '')} disabled={!editable}>
              <option value="">— Select —</option>
              {GENDERS.map((g) => <option key={g.value} value={g.value}>{g.label}</option>)}
            </select>
          </Field>
          {text('maritalStatus', { maxLength: 10 })}
          {text('nationality', { maxLength: 50 })}
          <Field id="emp-ssn" label={isEdit ? 'New SSN (leave blank to keep)' : 'SSN'} error={errors.ssn}>
            <input id="emp-ssn" value={values.ssn} maxLength={11} autoComplete="off" placeholder="NNN-NN-NNNN" onChange={(e) => set('ssn', e.target.value)} disabled={!editable} aria-invalid={errors.ssn ? true : undefined} />
          </Field>
          {text('email', { type: 'email', maxLength: 100 })}
          {text('phoneWork', { maxLength: 30 })}
          {text('phoneMobile', { maxLength: 30 })}
          {text('addressLine1', { maxLength: 200 })}
          {text('addressLine2', { maxLength: 200 })}
          {text('city', { maxLength: 100 })}
          {text('stateProvince', { maxLength: 100 })}
          {text('postalCode', { maxLength: 20 })}
          {text('countryCode', { maxLength: 3 })}
        </div>
      </fieldset>

      <fieldset>
        <legend>Job</legend>
        <div className="form-grid">
          {!isEdit && (
            <>
              <Field id="emp-hireDate" label="Hire date" required error={errors.hireDate}>
                <input id="emp-hireDate" type="date" value={values.hireDate} onChange={(e) => set('hireDate', e.target.value)} disabled={!editable} aria-invalid={errors.hireDate ? true : undefined} />
              </Field>
              <ReferenceDropdown source="departments" label="Department" required value={values.deptId} onChange={(v) => set('deptId', v)} error={errors.deptId} disabled={!editable} />
            </>
          )}
          <ReferenceDropdown source="job-titles" label="Job title" required value={values.jobId} onChange={(v) => set('jobId', v)} error={errors.jobId} disabled={!editable} />
          <ReferenceDropdown source="managers" label="Manager" excludeSelf value={values.managerEmpId} onChange={(v) => set('managerEmpId', v)} error={errors.managerEmpId} disabled={!editable} />
          {!isEdit && (
            <ReferenceDropdown source="locations" label="Location" value={values.locationCode} onChange={(v) => set('locationCode', v)} error={errors.locationCode} disabled={!editable} />
          )}
          <Field id="emp-employmentType" label="Employment type" error={errors.employmentType}>
            <select id="emp-employmentType" value={values.employmentType} onChange={(e) => set('employmentType', e.target.value as EmploymentType | '')} disabled={!editable}>
              {EMPLOYMENT_TYPES.map((t) => <option key={t} value={t}>{humanize(t)}</option>)}
            </select>
          </Field>
          {!isEdit && (
            <Field id="emp-initialSalary" label="Initial salary" error={errors.initialSalary}>
              <input id="emp-initialSalary" inputMode="decimal" value={values.initialSalary} onChange={(e) => set('initialSalary', e.target.value)} disabled={!editable} aria-invalid={errors.initialSalary ? true : undefined} />
            </Field>
          )}
          <Field id="emp-notes" label="Notes" error={errors.notes}>
            <textarea id="emp-notes" value={values.notes} maxLength={4000} onChange={(e) => set('notes', e.target.value)} disabled={!editable} />
          </Field>
        </div>
      </fieldset>

      {editable && (
        <div className="actions">
          <button type="submit" disabled={busy}>{isEdit ? 'Save' : 'Create employee'}</button>
          {!isEdit && <button type="button" onClick={() => navigate('/employees')} disabled={busy}>Cancel</button>}
        </div>
      )}
    </form>
  );
}

function Field({ id, label, required, error, children }: { id: string; label: string; required?: boolean; error?: string; children: ReactNode }) {
  return (
    <div className="field">
      <label htmlFor={id}>
        {label}
        {required && <span aria-hidden="true"> *</span>}
      </label>
      {children}
      {error && <span role="alert" className="field-error">{error}</span>}
    </div>
  );
}
