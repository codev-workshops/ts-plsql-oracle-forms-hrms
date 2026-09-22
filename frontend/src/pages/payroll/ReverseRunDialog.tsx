import { useState, type FormEvent } from 'react';
import { api } from '../../api/client';
import type { PayrollRun, PayrollRunReverseRequest } from '../../api/types';
import { useErrorHandler } from '../../app/useErrorHandler';
import { fieldErrors as zodFieldErrors, zodFor } from '../../validation/schema';

const FIELDS = ['reason'] as const;
const schema = zodFor('PayrollRunReverseRequest');

interface Props {
  run: PayrollRun;
  onReversed: (run: PayrollRun) => void;
  onClose: () => void;
}

/** `BTN_REVERSE` (HRMS_PAYROLL.xml) → `POST /api/payroll/runs/{runId}/reverse`. */
export function ReverseRunDialog({ run, onReversed, onClose }: Props) {
  const { handleError } = useErrorHandler();
  const [reason, setReason] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const parsed = schema.safeParse({ reason });
    if (!parsed.success) {
      setErrors(zodFieldErrors(parsed.error));
      return;
    }
    setErrors({});
    setBusy(true);
    try {
      onReversed(await api.payroll.reversePayrollRun(run.runId, parsed.data as PayrollRunReverseRequest));
    } catch (error) {
      setErrors(handleError(error, { fieldNames: FIELDS }).fieldErrors);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="dialog-overlay">
      <div role="dialog" aria-modal="true" aria-labelledby="reverse-run-title" className="dialog">
        <h3 id="reverse-run-title">Reverse payroll run #{run.runId}</h3>
        <p>All pay details of this run will be marked REVERSED and the pay period reopened.</p>
        <form onSubmit={submit} noValidate>
          <div className="field">
            <label htmlFor="reverseReason">Reason <span aria-hidden="true">*</span></label>
            <textarea id="reverseReason" value={reason} onChange={(e) => setReason(e.target.value)} aria-invalid={errors.reason ? true : undefined} />
            {errors.reason && <span role="alert" className="field-error">{errors.reason}</span>}
          </div>
          <div className="actions">
            <button type="submit" disabled={busy}>Reverse run</button>
            <button type="button" onClick={onClose} disabled={busy}>Cancel</button>
          </div>
        </form>
      </div>
    </div>
  );
}
