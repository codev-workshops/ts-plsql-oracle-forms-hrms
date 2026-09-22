import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { api } from '../../api/client';
import type { EmployeeDetail, JobTitleRef, SalaryChangeRequest, SalaryRecord } from '../../api/types';
import { useToast } from '../../app/ToastContext';
import { useErrorHandler } from '../../app/useErrorHandler';
import { referenceQueryKey } from '../../components/ReferenceDropdown';
import { getDto } from '../../validation/schema';
import { Dialog } from './Dialog';
import { enumValues, isRequired, money, parseForm, todayIso, type FormValues } from './formUtils';
import { employeeKeys } from './queryKeys';
import { formatMoney } from './salaryFormat';
import { TextField } from './TextField';

const DTO = 'SalaryChangeRequest';
const FIELDS = Object.keys(getDto(DTO).fields);

interface Props {
  employee: EmployeeDetail;
  current: SalaryRecord | null;
  onClose: () => void;
  onDone: (record: SalaryRecord) => void;
}

/**
 * `SALARY` block / `BTN_SALARY` (HRMS_EMPLOYEE.fmb) → `POST /api/employees/{id}/salary` (salary-module,
 * `PAYROLL:EDIT`). The grade-band banner mirrors the legacy `PKG_EMPLOYEE.CHECK_SALARY_GRADE` warning:
 * out-of-band is a warning only, the server records `outOfGradeBand` – never a rejection.
 */
export function SalaryDialog({ employee, current, onClose, onDone }: Props) {
  const qc = useQueryClient();
  const [form, setForm] = useState<FormValues>({
    effectiveDate: todayIso(),
    baseSalary: current?.baseSalary ?? '',
    currencyCode: current?.currencyCode ?? 'USD',
    payFrequency: current?.payFrequency ?? 'MONTHLY',
    salaryBasis: current?.salaryBasis ?? 'ANNUAL',
    changeReason: '',
  });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const { handleError } = useErrorHandler();
  const { push } = useToast();
  const set = (k: string) => (v: string) => setForm((o) => ({ ...o, [k]: v }));

  const jobs = useQuery<JobTitleRef[]>({
    queryKey: referenceQueryKey('job-titles', { active: true }),
    queryFn: () => api.reference.listJobTitles({ active: true }),
    staleTime: 5 * 60 * 1000,
  });
  const grade = jobs.data?.find((j) => j.jobId === employee.jobId);
  const amount = Number(form.baseSalary);
  const outOfBand = !!grade && form.baseSalary.trim() !== '' && Number.isFinite(amount) && (amount < Number(grade.gradeMinSalary) || amount > Number(grade.gradeMaxSalary));
  const pct = current && Number(current.baseSalary) > 0 && Number.isFinite(amount) ? ((amount - Number(current.baseSalary)) / Number(current.baseSalary)) * 100 : null;

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const parsed = parseForm<Omit<SalaryChangeRequest, 'baseSalary'> & { baseSalary: number | string }>(DTO, form);
    if (!parsed.ok) {
      setErrors(parsed.errors);
      return;
    }
    setErrors({});
    setBusy(true);
    try {
      const body: SalaryChangeRequest = { ...parsed.data, baseSalary: money(Number(parsed.data.baseSalary)) };
      const record = await api.employees.changeSalary(employee.id, body);
      qc.setQueryData(employeeKeys.salary(employee.id), record);
      void qc.invalidateQueries({ queryKey: employeeKeys.detail(employee.id) });
      push({ kind: 'success', message: `Salary changed to ${formatMoney(record.baseSalary, record.currencyCode)}` });
      onDone(record);
    } catch (error) {
      setErrors(handleError(error, { fieldNames: FIELDS }).fieldErrors);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog title="Change salary" onSubmit={submit} onClose={onClose} busy={busy} submitLabel="Apply change">
      <p>
        {employee.firstName} {employee.lastName} · {employee.jobTitle}
        {grade ? ` · grade ${grade.gradeCode} band ${formatMoney(grade.gradeMinSalary)} – ${formatMoney(grade.gradeMaxSalary)}` : ''}
        {current ? ` · current ${formatMoney(current.baseSalary, current.currencyCode)}` : ' · no active salary record'}
      </p>
      {outOfBand && grade && (
        <div role="status" className="banner banner-warning" data-testid="grade-band-warning">
          Warning: {formatMoney(amount)} is outside the {grade.gradeCode} grade band ({formatMoney(grade.gradeMinSalary)} – {formatMoney(grade.gradeMaxSalary)}). The change will be recorded as out of band.
        </div>
      )}
      <TextField id="sal-effectiveDate" label="Effective date" type="date" value={form.effectiveDate} onChange={set('effectiveDate')} required={isRequired(DTO, 'effectiveDate')} error={errors.effectiveDate} />
      <TextField id="sal-baseSalary" label="Base salary" type="number" step="0.01" min="0.01" value={form.baseSalary} onChange={set('baseSalary')} required={isRequired(DTO, 'baseSalary')} error={errors.baseSalary} />
      {pct !== null && form.baseSalary.trim() !== '' && (
        <p className="hint" data-testid="salary-change-pct">
          Change: {pct >= 0 ? '+' : ''}
          {pct.toFixed(2)}%
        </p>
      )}
      <TextField id="sal-currencyCode" label="Currency" value={form.currencyCode} onChange={set('currencyCode')} error={errors.currencyCode} />
      <TextField id="sal-payFrequency" label="Pay frequency" value={form.payFrequency} onChange={set('payFrequency')} options={enumValues(DTO, 'payFrequency')} error={errors.payFrequency} />
      <TextField id="sal-salaryBasis" label="Salary basis" value={form.salaryBasis} onChange={set('salaryBasis')} options={enumValues(DTO, 'salaryBasis')} error={errors.salaryBasis} />
      <TextField id="sal-changeReason" label="Change reason" value={form.changeReason} onChange={set('changeReason')} required={isRequired(DTO, 'changeReason')} error={errors.changeReason} />
    </Dialog>
  );
}
