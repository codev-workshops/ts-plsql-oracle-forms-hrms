import { useState, type FormEvent } from 'react';
import { api } from '../../api/client';
import type { PendingLeaveApproval } from '../../api/types';
import { formatDate } from '../../app/format';
import { useToast } from '../../app/ToastContext';
import { useErrorHandler } from '../../app/useErrorHandler';
import { fieldErrors as zodFieldErrors, zodFor } from '../../validation/schema';
import { describeHalfDay, formatDays } from './leaveFormat';

export type ApprovalAction = 'approve' | 'reject';

const schemas = { approve: zodFor('LeaveApproveRequest'), reject: zodFor('LeaveRejectRequest') };

/** `PKG_LEAVE.approve_leave_request` / `reject_leave_request` (comments required on reject). */
export function ApprovalDialog({ action, request, onClose, onDone }: { action: ApprovalAction; request: PendingLeaveApproval; onClose: () => void; onDone: () => void }) {
  const [comments, setComments] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const { handleError } = useErrorHandler();
  const { push } = useToast();
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const parsed = schemas[action].safeParse({ comments: action === 'approve' && !comments ? undefined : comments });
    if (!parsed.success) {
      setErrors(zodFieldErrors(parsed.error));
      return;
    }
    setErrors({});
    setBusy(true);
    try {
      if (action === 'approve') {
        await api.leave.approveLeaveRequest(request.requestId, comments.trim() ? { comments: comments.trim() } : undefined);
        push({ kind: 'success', message: 'Leave request approved' });
      } else {
        await api.leave.rejectLeaveRequest(request.requestId, { comments: comments.trim() });
        push({ kind: 'success', message: 'Leave request rejected' });
      }
      onDone();
    } catch (error) {
      const handled = handleError(error, { fieldNames: ['comments'] });
      setErrors(handled.fieldErrors);
    } finally {
      setBusy(false);
    }
  };
  const title = action === 'approve' ? 'Approve leave request' : 'Reject leave request';
  return (
    <div className="dialog-overlay">
      <div role="dialog" aria-modal="true" aria-labelledby="approval-dialog-title" className="dialog">
        <h3 id="approval-dialog-title">{title}</h3>
        <p>
          {request.empName} – {request.leaveTypeName} {formatDate(request.startDate)} – {formatDate(request.endDate)} ({formatDays(request.totalDays)} day(s), {describeHalfDay(request.halfDay, request.halfDayPeriod)})
        </p>
        {request.reason && <p className="hint">Reason: {request.reason}</p>}
        <form onSubmit={submit} noValidate>
          <div className="field">
            <label htmlFor="approvalComments">Comments{action === 'reject' && <span aria-hidden="true"> *</span>}</label>
            <textarea id="approvalComments" value={comments} onChange={(e) => setComments(e.target.value)} aria-invalid={errors.comments ? true : undefined} />
            {errors.comments && <span role="alert" className="field-error">{errors.comments}</span>}
          </div>
          <div className="actions">
            <button type="submit" disabled={busy}>{action === 'approve' ? 'Approve' : 'Reject'}</button>
            <button type="button" onClick={onClose} disabled={busy}>Cancel</button>
          </div>
        </form>
      </div>
    </div>
  );
}
