import { useState, type FormEvent } from 'react';
import { api } from '../../api/client';
import type { LeaveRequest } from '../../api/types';
import { formatDate } from '../../app/format';
import { useToast } from '../../app/ToastContext';
import { useErrorHandler } from '../../app/useErrorHandler';
import { fieldErrors as zodFieldErrors, zodFor } from '../../validation/schema';

const schema = zodFor('LeaveCancelRequest');

/** `BTN_CANCEL` (HRMS_LEAVE.xml) → `POST /api/leave/requests/{id}/cancel`. */
export function CancelRequestDialog({ request, onClose, onCancelled }: { request: LeaveRequest; onClose: () => void; onCancelled: () => void }) {
  const [reason, setReason] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const { handleError } = useErrorHandler();
  const { push } = useToast();
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const parsed = schema.safeParse({ reason: reason || undefined });
    if (!parsed.success) {
      setErrors(zodFieldErrors(parsed.error));
      return;
    }
    setErrors({});
    setBusy(true);
    try {
      await api.leave.cancelLeaveRequest(request.requestId, reason.trim() ? { reason: reason.trim() } : undefined);
      push({ kind: 'success', message: 'Leave request cancelled' });
      onCancelled();
    } catch (error) {
      const handled = handleError(error, { fieldNames: ['reason'] });
      setErrors(handled.fieldErrors);
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="dialog-overlay">
      <div role="dialog" aria-modal="true" aria-labelledby="cancel-dialog-title" className="dialog">
        <h3 id="cancel-dialog-title">Cancel leave request</h3>
        <p>
          {request.leaveTypeName} {formatDate(request.startDate)} – {formatDate(request.endDate)} ({request.status})
        </p>
        <form onSubmit={submit} noValidate>
          <div className="field">
            <label htmlFor="cancelReason">Reason</label>
            <textarea id="cancelReason" value={reason} onChange={(e) => setReason(e.target.value)} aria-invalid={errors.reason ? true : undefined} />
            {errors.reason && <span role="alert" className="field-error">{errors.reason}</span>}
          </div>
          <div className="actions">
            <button type="submit" disabled={busy}>Confirm cancel</button>
            <button type="button" onClick={onClose} disabled={busy}>Keep request</button>
          </div>
        </form>
      </div>
    </div>
  );
}
