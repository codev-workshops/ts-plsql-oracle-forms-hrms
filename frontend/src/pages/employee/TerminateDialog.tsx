import { useState, type FormEvent } from 'react';
import { api } from '../../api/client';
import type { EmployeeDetail } from '../../api/types';
import { useToast } from '../../app/ToastContext';
import { useErrorHandler } from '../../app/useErrorHandler';
import { fieldErrors as zodFieldErrors, zodFor } from '../../validation/schema';
import { compact } from './employeeShared';

const schema = zodFor('EmployeeTerminateRequest');
const FIELDS = ['effectiveDate', 'reason', 'comments'] as const;

/** `BTN_TERMINATE` (HRMS_EMPLOYEE.xml) → `POST /api/employees/{id}/terminate`. No DELETE exists (error -20504). */
export function TerminateDialog({ employee, onClose, onTerminated }: { employee: EmployeeDetail; onClose: () => void; onTerminated: (employee: EmployeeDetail) => void }) {
  const [effectiveDate, setEffectiveDate] = useState('');
  const [reason, setReason] = useState('');
  const [comments, setComments] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const { handleError } = useErrorHandler();
  const { push } = useToast();

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const candidate = compact({ effectiveDate, reason: reason.trim(), comments: comments.trim() }, 'EmployeeTerminateRequest');
    const parsed = schema.safeParse(candidate);
    if (!parsed.success) {
      setErrors(zodFieldErrors(parsed.error));
      return;
    }
    setErrors({});
    setBusy(true);
    try {
      const updated = await api.employees.terminateEmployee(employee.id, { effectiveDate, reason: reason.trim(), ...(comments.trim() ? { comments: comments.trim() } : {}) });
      push({ kind: 'success', message: `Employee ${employee.empNumber} terminated` });
      onTerminated(updated);
    } catch (error) {
      setErrors(handleError(error, { fieldNames: FIELDS }).fieldErrors);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="dialog-overlay">
      <div role="dialog" aria-modal="true" aria-labelledby="terminate-dialog-title" className="dialog">
        <h3 id="terminate-dialog-title">Terminate employee</h3>
        <p>
          {employee.lastName}, {employee.firstName} ({employee.empNumber})
        </p>
        <form onSubmit={submit} noValidate>
          <div className="field">
            <label htmlFor="termEffectiveDate">Effective date <span aria-hidden="true">*</span></label>
            <input id="termEffectiveDate" type="date" value={effectiveDate} onChange={(e) => setEffectiveDate(e.target.value)} aria-invalid={errors.effectiveDate ? true : undefined} />
            {errors.effectiveDate && <span role="alert" className="field-error">{errors.effectiveDate}</span>}
          </div>
          <div className="field">
            <label htmlFor="termReason">Reason <span aria-hidden="true">*</span></label>
            <input id="termReason" value={reason} maxLength={200} onChange={(e) => setReason(e.target.value)} aria-invalid={errors.reason ? true : undefined} />
            {errors.reason && <span role="alert" className="field-error">{errors.reason}</span>}
          </div>
          <div className="field">
            <label htmlFor="termComments">Comments</label>
            <textarea id="termComments" value={comments} maxLength={4000} onChange={(e) => setComments(e.target.value)} aria-invalid={errors.comments ? true : undefined} />
            {errors.comments && <span role="alert" className="field-error">{errors.comments}</span>}
          </div>
          <div className="actions">
            <button type="submit" disabled={busy}>Confirm termination</button>
            <button type="button" onClick={onClose} disabled={busy}>Cancel</button>
          </div>
        </form>
      </div>
    </div>
  );
}
