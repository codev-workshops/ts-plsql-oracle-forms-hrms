import { useState, type FormEvent } from 'react';
import { api } from '../../api/client';
import type { GoalCategory, GoalRequest, PerformanceReview } from '../../api/types';
import { useToast } from '../../app/ToastContext';
import { useErrorHandler } from '../../app/useErrorHandler';
import { fieldErrors as zodFieldErrors, getDto, zodFor } from '../../validation/schema';

const schema = zodFor('GoalRequest');
const fields = ['goalTitle', 'goalDescription', 'goalCategory', 'weightPct', 'targetDate'] as const;

export function GoalDialog({ review, onClose, onSaved }: { review: PerformanceReview; onClose: () => void; onSaved: () => void }) {
  const categories = (getDto('GoalRequest').fields.goalCategory.values ?? []) as GoalCategory[];
  const [form, setForm] = useState<GoalRequest>({ goalTitle: '', goalDescription: '', goalCategory: 'BUSINESS', weightPct: 0, targetDate: '' });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const { handleError } = useErrorHandler();
  const { push } = useToast();
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const parsed = schema.safeParse(Object.fromEntries(Object.entries(form).map(([key, value]) => [key, value === '' ? undefined : value])));
    if (!parsed.success) {
      setErrors(zodFieldErrors(parsed.error));
      return;
    }
    setErrors({});
    setBusy(true);
    try {
      await api.performance.addGoal(review.reviewId, { ...form, goalDescription: form.goalDescription || null, targetDate: form.targetDate || null });
      push({ kind: 'success', message: 'Goal added' });
      onSaved();
    } catch (error) {
      const handled = handleError(error, { fieldNames: fields });
      setErrors(handled.fieldErrors);
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="dialog-overlay">
      <div role="dialog" aria-modal="true" aria-labelledby="goal-dialog-title" className="dialog">
        <h3 id="goal-dialog-title">Add goal</h3>
        <form onSubmit={submit} noValidate>
          <div className="field"><label htmlFor="goalTitle">Goal title</label><input id="goalTitle" value={form.goalTitle} onChange={(e) => setForm((old) => ({ ...old, goalTitle: e.target.value }))} aria-invalid={errors.goalTitle ? true : undefined} />{errors.goalTitle && <span role="alert" className="field-error">{errors.goalTitle}</span>}</div>
          <div className="field"><label htmlFor="goalDescription">Description</label><textarea id="goalDescription" value={form.goalDescription ?? ''} onChange={(e) => setForm((old) => ({ ...old, goalDescription: e.target.value }))} /></div>
          <div className="field"><label htmlFor="goalCategory">Category</label><select id="goalCategory" value={form.goalCategory} onChange={(e) => setForm((old) => ({ ...old, goalCategory: e.target.value as GoalCategory }))}>{categories.map((value) => <option key={value}>{value}</option>)}</select></div>
          <div className="field"><label htmlFor="weightPct">Weight %</label><input id="weightPct" type="number" value={form.weightPct} onChange={(e) => setForm((old) => ({ ...old, weightPct: Number(e.target.value) }))} aria-invalid={errors.weightPct ? true : undefined} />{errors.weightPct && <span role="alert" className="field-error">{errors.weightPct}</span>}</div>
          <div className="field"><label htmlFor="targetDate">Target date</label><input id="targetDate" type="date" value={form.targetDate ?? ''} onChange={(e) => setForm((old) => ({ ...old, targetDate: e.target.value }))} /></div>
          <div className="actions"><button type="submit" disabled={busy}>Save</button><button type="button" onClick={onClose} disabled={busy}>Cancel</button></div>
        </form>
      </div>
    </div>
  );
}
