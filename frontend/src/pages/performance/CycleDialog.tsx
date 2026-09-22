import { useEffect, useState, type FormEvent } from 'react';
import { api } from '../../api/client';
import type { ReviewCycle, ReviewCycleRequest } from '../../api/types';
import { useToast } from '../../app/ToastContext';
import { useErrorHandler } from '../../app/useErrorHandler';
import { fieldErrors as zodFieldErrors, getDto, zodFor } from '../../validation/schema';

const fields = ['cycleName', 'cycleYear', 'startDate', 'endDate', 'selfReviewDue', 'managerReviewDue', 'calibrationDue'] as const;
const schema = zodFor('ReviewCycleRequest');

export function CycleDialog({ cycle, onClose, onSaved }: { cycle?: ReviewCycle; onClose: () => void; onSaved: () => void }) {
  const [form, setForm] = useState<ReviewCycleRequest>({
    cycleName: cycle?.cycleName ?? '',
    cycleYear: cycle?.cycleYear ?? new Date().getFullYear(),
    startDate: cycle?.startDate ?? '',
    endDate: cycle?.endDate ?? '',
    selfReviewDue: cycle?.selfReviewDue ?? '',
    managerReviewDue: cycle?.managerReviewDue ?? '',
    calibrationDue: cycle?.calibrationDue ?? '',
  });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const { handleError } = useErrorHandler();
  const { push } = useToast();
  const endDateFormat = getDto('ReviewCycleRequest').fields.endDate.messages.format;

  useEffect(() => {
    if (!cycle) return;
    setForm({
      cycleName: cycle.cycleName,
      cycleYear: cycle.cycleYear,
      startDate: cycle.startDate,
      endDate: cycle.endDate,
      selfReviewDue: cycle.selfReviewDue ?? '',
      managerReviewDue: cycle.managerReviewDue ?? '',
      calibrationDue: cycle.calibrationDue ?? '',
    });
  }, [cycle]);

  const set = (field: keyof ReviewCycleRequest, value: string | number) => setForm((old) => ({ ...old, [field]: value }));

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    const parsedValue = Object.fromEntries(Object.entries(form).map(([key, value]) => [key, value === '' ? undefined : value]));
    const parsed = schema.safeParse(parsedValue);
    const nextErrors = parsed.success ? {} : zodFieldErrors(parsed.error);
    if (form.startDate && form.endDate && form.endDate < form.startDate) nextErrors.endDate = endDateFormat ?? 'Invalid date';
    if (Object.keys(nextErrors).length) {
      setErrors(nextErrors);
      return;
    }
    setErrors({});
    setBusy(true);
    try {
      const value = {
        ...form,
        selfReviewDue: form.selfReviewDue || null,
        managerReviewDue: form.managerReviewDue || null,
        calibrationDue: form.calibrationDue || null,
      };
      if (cycle) {
        await api.performance.updateCycle(cycle.cycleId, value);
        push({ kind: 'success', message: 'Cycle updated' });
      } else {
        await api.performance.createCycle(value);
        push({ kind: 'success', message: 'Cycle created' });
      }
      onSaved();
    } catch (error) {
      const handled = handleError(error, { fieldNames: fields });
      if (Object.keys(handled.fieldErrors).length) setErrors(handled.fieldErrors);
    } finally {
      setBusy(false);
    }
  };

  const input = (field: keyof ReviewCycleRequest, label: string, type: string) => (
    <div className="field" key={field}>
      <label htmlFor={`cycle-${field}`}>{label}</label>
      <input
        id={`cycle-${field}`}
        type={type}
        value={form[field] ?? ''}
        onChange={(event) => set(field, type === 'number' ? Number(event.target.value) : event.target.value)}
        aria-invalid={errors[field] ? true : undefined}
      />
      {errors[field] && <span role="alert" className="field-error">{errors[field]}</span>}
    </div>
  );

  return (
    <div className="dialog-overlay">
      <div role="dialog" aria-modal="true" aria-labelledby="cycle-dialog-title" className="dialog">
        <h2 id="cycle-dialog-title">{cycle ? 'Edit cycle' : 'New cycle'}</h2>
        <form onSubmit={onSubmit} noValidate>
          {input('cycleName', 'Cycle name', 'text')}
          {input('cycleYear', 'Cycle year', 'number')}
          {input('startDate', 'Start date', 'date')}
          {input('endDate', 'End date', 'date')}
          {input('selfReviewDue', 'Self-review due', 'date')}
          {input('managerReviewDue', 'Manager review due', 'date')}
          {input('calibrationDue', 'Calibration due', 'date')}
          <div className="actions">
            <button type="submit" disabled={busy}>Save</button>
            <button type="button" onClick={onClose} disabled={busy}>Cancel</button>
          </div>
        </form>
      </div>
    </div>
  );
}
