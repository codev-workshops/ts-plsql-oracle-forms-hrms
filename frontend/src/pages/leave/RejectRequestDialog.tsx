import { useState, type FormEvent } from 'react';
import { api } from '../../api/client';
import type { PendingLeaveApproval } from '../../api/types';
import { formatDate } from '../../app/format';
import { useToast } from '../../app/ToastContext';
import { useErrorHandler } from '../../app/useErrorHandler';
import { fieldErrors as zodFieldErrors, zodFor } from '../../validation/schema';

const schema = zodFor('LeaveRejectRequest');
const fields = ['comments'] as const;

export function RejectRequestDialog({ approval, onClose, onRejected }: { approval: PendingLeaveApproval; onClose: () => void; onRejected: () => void }) {
  const [comments, setComments] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const { handleError } = useErrorHandler();
  const { push } = useToast();

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const parsed = schema.safeParse({ comments });
    if (!parsed.success) {
      setErrors(zodFieldErrors(parsed.error));
      return;
    }
    setErrors({});
    setBusy(true);
    try {
      await api.leave.rejectLeaveRequest(approval.requestId, { comments: comments.trim() });
      push({ kind: 'success', message: `Rejected leave for ${approval.empName}` });
      onRejected();
    } catch (error) {
      setErrors(handleError(error, { fieldNames: fields }).fieldErrors);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="dialog-overlay">
      <div role="dialog" aria-modal="true" aria-labelledby="reject-dialog-title" className="dialog">
        <h3 id="reject-dialog-title">Reject leave request</h3>
        <p>
          {approval.empName}: {approval.leaveTypeName}, {formatDate(approval.startDate)} – {formatDate(approval.endDate)}
        </p>
        <form onSubmit={submit} noValidate>
          <div className="field">
            <label htmlFor="rejectComments">Rejection reason *</label>
            <textarea id="rejectComments" value={comments} onChange={(e) => setComments(e.target.value)} aria-invalid={errors.comments ? true : undefined} />
            {errors.comments && <span role="alert" className="field-error">{errors.comments}</span>}
          </div>
          <div className="actions">
            <button type="submit" disabled={busy}>Reject</button>
            <button type="button" onClick={onClose} disabled={busy}>Back</button>
          </div>
        </form>
      </div>
    </div>
  );
}
