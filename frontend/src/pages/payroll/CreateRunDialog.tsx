import { useState, type FormEvent } from 'react';
import { api } from '../../api/client';
import type { PayPeriod, PayrollRun, PayrollRunCreateRequest, RunType } from '../../api/types';
import { useErrorHandler } from '../../app/useErrorHandler';
import { fieldErrors as zodFieldErrors, getDto, zodFor } from '../../validation/schema';
import { humanize } from './payrollShared';

const FIELDS = ['runType'] as const;
const RUN_TYPES = getDto('PayrollRunCreateRequest').fields.runType.values as RunType[];
const schema = zodFor('PayrollRunCreateRequest');

interface Props {
  period: PayPeriod;
  onCreated: (run: PayrollRun) => void;
  onClose: () => void;
}

/** `BTN_CREATE_RUN` (HRMS_PAYROLL.xml) → `POST /api/payroll/periods/{periodId}/runs`. */
export function CreateRunDialog({ period, onCreated, onClose }: Props) {
  const { handleError } = useErrorHandler();
  const [runType, setRunType] = useState<RunType | ''>('REGULAR');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const candidate: Record<string, unknown> = runType ? { runType } : {};
    const parsed = schema.safeParse(candidate);
    if (!parsed.success) {
      setErrors(zodFieldErrors(parsed.error));
      return;
    }
    setErrors({});
    setBusy(true);
    try {
      const run = await api.payroll.createPayrollRun(period.periodId, parsed.data as PayrollRunCreateRequest);
      onCreated(run);
    } catch (error) {
      setErrors(handleError(error, { fieldNames: FIELDS }).fieldErrors);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="dialog-overlay">
      <div role="dialog" aria-modal="true" aria-labelledby="create-run-title" className="dialog">
        <h3 id="create-run-title">Create payroll run</h3>
        <p>{period.periodName} · pay date {period.payDate}</p>
        <form onSubmit={submit} noValidate>
          <div className="field">
            <label htmlFor="runType">Run type <span aria-hidden="true">*</span></label>
            <select id="runType" value={runType} onChange={(e) => setRunType(e.target.value as RunType | '')} aria-invalid={errors.runType ? true : undefined}>
              <option value="">— Select —</option>
              {RUN_TYPES.map((t) => <option key={t} value={t}>{humanize(t)}</option>)}
            </select>
            {errors.runType && <span role="alert" className="field-error">{errors.runType}</span>}
          </div>
          <div className="actions">
            <button type="submit" disabled={busy}>Create run</button>
            <button type="button" onClick={onClose} disabled={busy}>Cancel</button>
          </div>
        </form>
      </div>
    </div>
  );
}
