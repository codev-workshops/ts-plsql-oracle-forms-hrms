import { useState, type FormEvent } from 'react';
import { api } from '../../api/client';
import type { GoalProgressRequest, GoalStatus, PerformanceGoal } from '../../api/types';
import { useToast } from '../../app/ToastContext';
import { useErrorHandler } from '../../app/useErrorHandler';
import { fieldErrors as zodFieldErrors, getDto, zodFor } from '../../validation/schema';

const schema = zodFor('GoalProgressRequest');
const statuses = (getDto('GoalProgressRequest').fields.status.values ?? []) as GoalStatus[];

export function GoalProgressDialog({ goal, onClose, onSaved }: { goal: PerformanceGoal; onClose: () => void; onSaved: () => void }) {
  const [form, setForm] = useState<GoalProgressRequest>({ progressPct: goal.progressPct, status: null, comments: '' });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const { handleError } = useErrorHandler();
  const { push } = useToast();
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const parsed = schema.safeParse({ ...form, status: form.status ?? undefined, comments: form.comments || undefined });
    if (!parsed.success) {
      setErrors(zodFieldErrors(parsed.error));
      return;
    }
    setErrors({});
    setBusy(true);
    try {
      await api.performance.updateGoalProgress(goal.goalId, { ...form, status: form.status || null, comments: form.comments || undefined });
      push({ kind: 'success', message: 'Progress updated' });
      onSaved();
    } catch (error) {
      const handled = handleError(error, { fieldNames: ['progressPct', 'status', 'comments'] });
      setErrors(handled.fieldErrors);
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="dialog-overlay">
      <div role="dialog" aria-modal="true" aria-labelledby="progress-dialog-title" className="dialog">
        <h3 id="progress-dialog-title">Update progress</h3>
        <form onSubmit={submit} noValidate>
          <div className="field"><label htmlFor="progressPct">Progress %</label><input id="progressPct" type="number" value={form.progressPct} onChange={(e) => setForm((old) => ({ ...old, progressPct: Number(e.target.value) }))} aria-invalid={errors.progressPct ? true : undefined} />{errors.progressPct && <span role="alert" className="field-error">{errors.progressPct}</span>}</div>
          <div className="field"><label htmlFor="progressStatus">Status</label><select id="progressStatus" value={form.status ?? ''} onChange={(e) => setForm((old) => ({ ...old, status: (e.target.value || null) as GoalStatus | null }))}><option value="">Derive from progress</option>{statuses.map((status) => <option key={status}>{status}</option>)}</select></div>
          <div className="field"><label htmlFor="progressComments">Comments</label><textarea id="progressComments" value={form.comments ?? ''} onChange={(e) => setForm((old) => ({ ...old, comments: e.target.value }))} /></div>
          <p className="hint">100% marks the goal COMPLETED; any progress above 0 marks it IN_PROGRESS</p>
          <div className="actions"><button type="submit" disabled={busy}>Save</button><button type="button" onClick={onClose} disabled={busy}>Cancel</button></div>
        </form>
      </div>
    </div>
  );
}
