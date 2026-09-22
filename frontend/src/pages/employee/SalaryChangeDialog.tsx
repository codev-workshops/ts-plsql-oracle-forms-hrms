import { useQuery } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { api } from '../../api/client';
import type { EmployeeDetail, PayFrequency, SalaryBasis, SalaryChangeRequest } from '../../api/types';
import { formatMoney } from '../../app/format';
import { useToast } from '../../app/ToastContext';
import { useErrorHandler } from '../../app/useErrorHandler';
import { referenceQueryKey } from '../../components/ReferenceDropdown';
import { fieldErrors as zodFieldErrors, zodFor } from '../../validation/schema';
import { PAY_FREQUENCIES, SALARY_BASES, compact, humanize, isOutOfGradeBand, salaryCurrentKey, toMoney } from './employeeShared';

const schema = zodFor('SalaryChangeRequest');
const FIELDS = ['effectiveDate', 'baseSalary', 'currencyCode', 'payFrequency', 'salaryBasis', 'changeReason'] as const;

/**
 * `SALARY` block save (HRMS_EMPLOYEE.fmb) → salary-module `POST /api/employees/{id}/salary`.
 * The grade-band check is advisory (Forms showed a warning alert, never blocked): the banner
 * appears when the amount is outside the job grade's min/max but submission proceeds.
 */
export function SalaryChangeDialog({ employee, onClose, onChanged }: { employee: EmployeeDetail; onClose: () => void; onChanged: () => void }) {
  const [effectiveDate, setEffectiveDate] = useState('');
  const [baseSalary, setBaseSalary] = useState('');
  const [currencyCode, setCurrencyCode] = useState('');
  const [payFrequency, setPayFrequency] = useState<PayFrequency | ''>('');
  const [salaryBasis, setSalaryBasis] = useState<SalaryBasis | ''>('');
  const [changeReason, setChangeReason] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const { handleError } = useErrorHandler();
  const { push } = useToast();

  const current = useQuery({ queryKey: salaryCurrentKey(employee.id), queryFn: () => api.salary.getCurrentSalary(employee.id), retry: false });
  const jobs = useQuery({ queryKey: referenceQueryKey('job-titles', { active: false }), queryFn: () => api.reference.listJobTitles({ active: false }) });
  const grade = jobs.data?.find((j) => j.jobId === employee.jobId);
  const outOfBand = grade ? isOutOfGradeBand(baseSalary, grade.gradeMinSalary, grade.gradeMaxSalary) : false;

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const candidate = compact({ effectiveDate, baseSalary: baseSalary.trim(), currencyCode: currencyCode.trim().toUpperCase(), payFrequency, salaryBasis, changeReason: changeReason.trim() }, 'SalaryChangeRequest');
    const parsed = schema.safeParse(candidate);
    if (!parsed.success) {
      setErrors(zodFieldErrors(parsed.error));
      return;
    }
    setErrors({});
    setBusy(true);
    try {
      const body = { ...candidate, baseSalary: toMoney(baseSalary) } as unknown as SalaryChangeRequest;
      const record = await api.salary.changeSalary(employee.id, body);
      push({ kind: 'success', message: `Salary set to ${formatMoney(record.baseSalary)} ${record.currencyCode}` });
      onChanged();
    } catch (error) {
      setErrors(handleError(error, { fieldNames: FIELDS }).fieldErrors);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="dialog-overlay">
      <div role="dialog" aria-modal="true" aria-labelledby="salary-dialog-title" className="dialog">
        <h3 id="salary-dialog-title">Change salary</h3>
        <p>
          {employee.lastName}, {employee.firstName} ({employee.empNumber}) · {employee.jobTitle}
          {current.isSuccess && <> · current {formatMoney(current.data.baseSalary)} {current.data.currencyCode}</>}
        </p>
        {grade && (
          <p className="hint" data-testid="grade-band">
            Grade {grade.gradeCode} band: {formatMoney(grade.gradeMinSalary)} – {formatMoney(grade.gradeMaxSalary)}
          </p>
        )}
        {outOfBand && grade && (
          <p role="status" className="banner banner-warning" data-testid="grade-band-warning">
            Warning: {formatMoney(toMoney(baseSalary))} is outside the {grade.gradeCode} grade band ({formatMoney(grade.gradeMinSalary)} – {formatMoney(grade.gradeMaxSalary)}). You can still save.
          </p>
        )}
        <form onSubmit={submit} noValidate>
          <div className="field">
            <label htmlFor="salEffectiveDate">Effective date <span aria-hidden="true">*</span></label>
            <input id="salEffectiveDate" type="date" value={effectiveDate} onChange={(e) => setEffectiveDate(e.target.value)} aria-invalid={errors.effectiveDate ? true : undefined} />
            {errors.effectiveDate && <span role="alert" className="field-error">{errors.effectiveDate}</span>}
          </div>
          <div className="field">
            <label htmlFor="salBaseSalary">Base salary <span aria-hidden="true">*</span></label>
            <input id="salBaseSalary" inputMode="decimal" value={baseSalary} onChange={(e) => setBaseSalary(e.target.value)} aria-invalid={errors.baseSalary ? true : undefined} aria-describedby={outOfBand ? 'grade-band-warning' : undefined} />
            {errors.baseSalary && <span role="alert" className="field-error">{errors.baseSalary}</span>}
          </div>
          <div className="field">
            <label htmlFor="salCurrency">Currency</label>
            <input id="salCurrency" value={currencyCode} maxLength={3} placeholder={current.data?.currencyCode ?? 'USD'} onChange={(e) => setCurrencyCode(e.target.value)} aria-invalid={errors.currencyCode ? true : undefined} />
            {errors.currencyCode && <span role="alert" className="field-error">{errors.currencyCode}</span>}
          </div>
          <div className="field">
            <label htmlFor="salPayFrequency">Pay frequency</label>
            <select id="salPayFrequency" value={payFrequency} onChange={(e) => setPayFrequency(e.target.value as PayFrequency | '')}>
              <option value="">— Keep current —</option>
              {PAY_FREQUENCIES.map((f) => <option key={f} value={f}>{humanize(f)}</option>)}
            </select>
          </div>
          <div className="field">
            <label htmlFor="salBasis">Salary basis</label>
            <select id="salBasis" value={salaryBasis} onChange={(e) => setSalaryBasis(e.target.value as SalaryBasis | '')}>
              <option value="">— Keep current —</option>
              {SALARY_BASES.map((b) => <option key={b} value={b}>{humanize(b)}</option>)}
            </select>
          </div>
          <div className="field">
            <label htmlFor="salChangeReason">Change reason <span aria-hidden="true">*</span></label>
            <input id="salChangeReason" value={changeReason} maxLength={200} onChange={(e) => setChangeReason(e.target.value)} aria-invalid={errors.changeReason ? true : undefined} />
            {errors.changeReason && <span role="alert" className="field-error">{errors.changeReason}</span>}
          </div>
          <div className="actions">
            <button type="submit" disabled={busy}>Save salary</button>
            <button type="button" onClick={onClose} disabled={busy}>Cancel</button>
          </div>
        </form>
      </div>
    </div>
  );
}
