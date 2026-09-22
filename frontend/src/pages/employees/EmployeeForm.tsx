import { useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { api } from '../../api/client';
import type { EmployeeCreateRequest, EmployeeDetail, EmployeeUpdateRequest } from '../../api/types';
import { useToast } from '../../app/ToastContext';
import { useErrorHandler } from '../../app/useErrorHandler';
import { ReferenceDropdown } from '../../components/ReferenceDropdown';
import { getDto } from '../../validation/schema';
import { enumValues, isRequired, money, parseForm, type FormValues } from './formUtils';
import { employeeKeys } from './queryKeys';
import { TextField } from './TextField';

type Mode = { kind: 'create' } | { kind: 'edit'; employee: EmployeeDetail };

const CREATE_DTO = 'EmployeeCreateRequest';
const UPDATE_DTO = 'EmployeeUpdateRequest';

function initial(mode: Mode): FormValues {
  if (mode.kind === 'create') {
    const v: FormValues = {};
    for (const name of Object.keys(getDto(CREATE_DTO).fields)) v[name] = '';
    v.employmentType = 'FULL_TIME';
    return v;
  }
  const e = mode.employee;
  const v: FormValues = {};
  for (const name of Object.keys(getDto(UPDATE_DTO).fields)) {
    const raw = (e as unknown as Record<string, unknown>)[name];
    v[name] = raw === null || raw === undefined ? '' : String(raw);
  }
  v.ssn = ''; // write-only: blank = unchanged
  return v;
}

/**
 * `EMPLOYEE` block of HRMS_EMPLOYEE.fmb (COMPONENT_MAPPING.md §3): create → `POST /api/employees`,
 * edit → `PUT /api/employees/{id}` with `If-Match: "<version>"`. Fields, requiredness and rules
 * come from validation-schema.json (`EmployeeCreateRequest` / `EmployeeUpdateRequest`);
 * `empNumber` is server-assigned and `hireDate`/`deptId` are only writable at hire (transfer
 * moves departments).
 */
export function EmployeeForm({ mode, onSaved, onCancel }: { mode: Mode; onSaved: (employee: EmployeeDetail) => void; onCancel: () => void }) {
  const dto = mode.kind === 'create' ? CREATE_DTO : UPDATE_DTO;
  const qc = useQueryClient();
  const [form, setForm] = useState<FormValues>(() => initial(mode));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const { handleError } = useErrorHandler();
  const { push } = useToast();
  const set = (key: string) => (value: string) => setForm((old) => ({ ...old, [key]: value }));
  const req = (field: string) => isRequired(dto, field);
  const fieldNames = Object.keys(getDto(dto).fields);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    try {
      let saved: EmployeeDetail;
      if (mode.kind === 'create') {
        const parsed = parseForm<EmployeeCreateRequest & { initialSalary?: number | string }>(CREATE_DTO, form);
        if (!parsed.ok) {
          setErrors(parsed.errors);
          return;
        }
        setErrors({});
        const body: EmployeeCreateRequest = {
          ...parsed.data,
          initialSalary: parsed.data.initialSalary === undefined ? undefined : money(Number(parsed.data.initialSalary)),
        };
        saved = await api.employees.createEmployee(body);
        push({ kind: 'success', message: `Employee ${saved.empNumber} created` });
      } else {
        const parsed = parseForm<EmployeeUpdateRequest>(UPDATE_DTO, form);
        if (!parsed.ok) {
          setErrors(parsed.errors);
          return;
        }
        setErrors({});
        saved = await api.employees.updateEmployee(mode.employee.id, mode.employee.version, parsed.data);
        push({ kind: 'success', message: 'Employee updated' });
      }
      qc.setQueryData(employeeKeys.detail(saved.id), saved);
      void qc.invalidateQueries({ queryKey: employeeKeys.all });
      onSaved(saved);
    } catch (error) {
      setErrors(handleError(error, { fieldNames }).fieldErrors);
    } finally {
      setBusy(false);
    }
  };

  const isCreate = mode.kind === 'create';
  const excludeSelf = !isCreate;
  return (
    <form onSubmit={submit} noValidate aria-label={isCreate ? 'New employee' : 'Edit employee'} className="employee-form">
      <fieldset disabled={busy}>
        <legend>Personal</legend>
        <TextField id="firstName" label="First name" value={form.firstName} onChange={set('firstName')} required={req('firstName')} error={errors.firstName} />
        <TextField id="middleName" label="Middle name" value={form.middleName ?? ''} onChange={set('middleName')} error={errors.middleName} />
        <TextField id="lastName" label="Last name" value={form.lastName} onChange={set('lastName')} required={req('lastName')} error={errors.lastName} />
        <TextField id="dateOfBirth" label="Date of birth" type="date" value={form.dateOfBirth ?? ''} onChange={set('dateOfBirth')} error={errors.dateOfBirth} />
        <TextField id="gender" label="Gender" value={form.gender ?? ''} onChange={set('gender')} options={enumValues(dto, 'gender')} error={errors.gender} />
        <TextField id="maritalStatus" label="Marital status" value={form.maritalStatus ?? ''} onChange={set('maritalStatus')} error={errors.maritalStatus} />
        <TextField id="nationality" label="Nationality" value={form.nationality ?? ''} onChange={set('nationality')} error={errors.nationality} />
        <TextField
          id="ssn"
          label={isCreate ? 'SSN' : `SSN (on file: ${mode.employee.ssnLast4 ? `•••-••-${mode.employee.ssnLast4}` : 'none'}; leave blank to keep)`}
          type="password"
          autoComplete="off"
          value={form.ssn ?? ''}
          onChange={set('ssn')}
          error={errors.ssn}
        />
      </fieldset>
      <fieldset disabled={busy}>
        <legend>Contact</legend>
        <TextField id="email" label="E-mail" type="email" value={form.email ?? ''} onChange={set('email')} error={errors.email} />
        <TextField id="phoneWork" label="Work phone" type="tel" value={form.phoneWork ?? ''} onChange={set('phoneWork')} error={errors.phoneWork} />
        <TextField id="phoneMobile" label="Mobile phone" type="tel" value={form.phoneMobile ?? ''} onChange={set('phoneMobile')} error={errors.phoneMobile} />
        <TextField id="addressLine1" label="Address line 1" value={form.addressLine1 ?? ''} onChange={set('addressLine1')} error={errors.addressLine1} />
        <TextField id="addressLine2" label="Address line 2" value={form.addressLine2 ?? ''} onChange={set('addressLine2')} error={errors.addressLine2} />
        <TextField id="city" label="City" value={form.city ?? ''} onChange={set('city')} error={errors.city} />
        <TextField id="stateProvince" label="State / province" value={form.stateProvince ?? ''} onChange={set('stateProvince')} error={errors.stateProvince} />
        <TextField id="postalCode" label="Postal code" value={form.postalCode ?? ''} onChange={set('postalCode')} error={errors.postalCode} />
        <TextField id="countryCode" label="Country code" value={form.countryCode ?? ''} onChange={set('countryCode')} error={errors.countryCode} />
      </fieldset>
      <fieldset disabled={busy}>
        <legend>Employment</legend>
        {isCreate && (
          <>
            <TextField id="hireDate" label="Hire date" type="date" value={form.hireDate} onChange={set('hireDate')} required={req('hireDate')} error={errors.hireDate} />
            <ReferenceDropdown source="departments" label="Department" name="deptId" value={form.deptId || null} onChange={(v) => set('deptId')(v ?? '')} required={req('deptId')} error={errors.deptId} />
          </>
        )}
        <ReferenceDropdown source="job-titles" label="Job title" name="jobId" value={form.jobId || null} onChange={(v) => set('jobId')(v ?? '')} required={req('jobId')} error={errors.jobId} />
        <ReferenceDropdown source="managers" label="Manager" name="managerEmpId" value={form.managerEmpId || null} onChange={(v) => set('managerEmpId')(v ?? '')} excludeSelf={excludeSelf} error={errors.managerEmpId} />
        {isCreate && (
          <ReferenceDropdown source="locations" label="Location" name="locationCode" value={form.locationCode || null} onChange={(v) => set('locationCode')(v ?? '')} error={errors.locationCode} />
        )}
        <TextField id="employmentType" label="Employment type" value={form.employmentType ?? ''} onChange={set('employmentType')} options={enumValues(dto, 'employmentType')} error={errors.employmentType} />
        {isCreate && (
          <TextField id="initialSalary" label="Initial salary" type="number" step="0.01" min="0" value={form.initialSalary ?? ''} onChange={set('initialSalary')} error={errors.initialSalary} />
        )}
        <TextField id="notes" label="Notes" multiline value={form.notes ?? ''} onChange={set('notes')} error={errors.notes} />
      </fieldset>
      <div className="actions">
        <button type="submit" disabled={busy}>
          {isCreate ? 'Create employee' : 'Save changes'}
        </button>
        <button type="button" onClick={onCancel} disabled={busy}>
          Cancel
        </button>
      </div>
    </form>
  );
}
