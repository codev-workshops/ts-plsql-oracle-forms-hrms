import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/client';
import type { HalfDayPeriod, LeaveRequestCreateRequest } from '../../api/types';
import { useToast } from '../../app/ToastContext';
import { useErrorHandler } from '../../app/useErrorHandler';
import { ReferenceDropdown } from '../../components/ReferenceDropdown';
import { fieldErrors as zodFieldErrors, getDto, zodFormFor } from '../../validation/schema';
import { leaveKeys } from './leaveKeys';

/**
 * HRMS_LEAVE "Submit Request" block (COMPONENT_MAPPING.md §5). Field rules come from
 * validation-schema.json `LeaveRequestCreateRequest`; the live business-day count and
 * balance are read from `/api/leave/business-days` and `/api/leave/balances/mine`
 * (server = PKG_LEAVE_MGMT.calculate_business_days / get_leave_balance). Balance,
 * overlap, tenure and holiday rules stay server-side and surface via useErrorHandler.
 */
const dto = getDto('LeaveRequestCreateRequest');
const schema = zodFormFor('LeaveRequestCreateRequest');
const fields = ['leaveTypeId', 'startDate', 'endDate', 'halfDay', 'halfDayPeriod', 'reason'] as const;
const periods = (dto.fields.halfDayPeriod.values ?? []) as HalfDayPeriod[];

interface FormState {
  leaveTypeId: string | null;
  startDate: string;
  endDate: string;
  halfDay: boolean;
  halfDayPeriod: HalfDayPeriod | '';
  reason: string;
}

const EMPTY: FormState = { leaveTypeId: null, startDate: '', endDate: '', halfDay: false, halfDayPeriod: '', reason: '' };

function toRequest(form: FormState): LeaveRequestCreateRequest {
  return {
    leaveTypeId: Number(form.leaveTypeId),
    startDate: form.startDate,
    endDate: form.halfDay ? form.startDate : form.endDate,
    halfDay: form.halfDay,
    halfDayPeriod: form.halfDay && form.halfDayPeriod ? form.halfDayPeriod : null,
    reason: form.reason.trim() || null,
  };
}

/** Half-day shape (contract `LeaveRequestCreateRequest` description); messages from the JSON. */
function halfDayErrors(form: FormState): Record<string, string> {
  if (!form.halfDay) return {};
  const out: Record<string, string> = {};
  if (form.startDate && form.endDate && form.startDate !== form.endDate) out.endDate = dto.fields.halfDay.messages.format ?? '';
  if (!form.halfDayPeriod) out.halfDayPeriod = dto.fields.halfDayPeriod.messages.required ?? '';
  return out;
}

export function SubmitRequestTab() {
  const [form, setForm] = useState<FormState>(EMPTY);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const qc = useQueryClient();
  const navigate = useNavigate();
  const { handleError } = useErrorHandler();
  const { push } = useToast();

  const endDate = form.halfDay ? form.startDate : form.endDate;
  const rangeValid = Boolean(form.startDate && endDate && form.startDate <= endDate);

  const businessDays = useQuery({
    queryKey: leaveKeys.businessDays(form.startDate, endDate),
    queryFn: () => api.leave.getBusinessDays({ start: form.startDate, end: endDate }),
    enabled: rangeValid,
  });
  const balances = useQuery({ queryKey: leaveKeys.balances, queryFn: () => api.leave.getMyLeaveBalances() });
  const balance = balances.data?.find((b) => String(b.leaveTypeId) === form.leaveTypeId) ?? null;
  const requestedDays = form.halfDay ? 0.5 : businessDays.data?.businessDays ?? null;

  const update = <K extends keyof FormState>(key: K, value: FormState[K]) => setForm((old) => ({ ...old, [key]: value }));

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const body = toRequest(form);
    const parsed = schema.safeParse({
      ...body,
      leaveTypeId: form.leaveTypeId ? body.leaveTypeId : undefined,
      halfDayPeriod: body.halfDayPeriod ?? undefined,
      reason: body.reason ?? undefined,
    });
    const shape = halfDayErrors(form);
    if (!parsed.success || Object.keys(shape).length > 0) {
      setErrors({ ...(parsed.success ? {} : zodFieldErrors(parsed.error)), ...shape });
      return;
    }
    setErrors({});
    setBusy(true);
    try {
      const created = await api.leave.submitLeaveRequest(body);
      push({ kind: 'success', message: created.status === 'APPROVED' ? 'Leave request auto-approved' : 'Leave request submitted' });
      await Promise.all([qc.invalidateQueries({ queryKey: leaveKeys.mine }), qc.invalidateQueries({ queryKey: leaveKeys.balances })]);
      setForm(EMPTY);
      navigate('/leave');
    } catch (error) {
      setErrors(handleError(error, { fieldNames: fields }).fieldErrors);
    } finally {
      setBusy(false);
    }
  };

  return (
    <form className="submit-request-tab" onSubmit={submit} noValidate aria-label="Submit leave request">
      <ReferenceDropdown source="leave-types" label="Leave type" value={form.leaveTypeId} onChange={(v) => update('leaveTypeId', v)} required error={errors.leaveTypeId} />

      <div className="field">
        <label htmlFor="startDate">Start date *</label>
        <input id="startDate" type="date" value={form.startDate} onChange={(e) => update('startDate', e.target.value)} aria-invalid={errors.startDate ? true : undefined} />
        {errors.startDate && <span role="alert" className="field-error">{errors.startDate}</span>}
      </div>

      <div className="field">
        <label htmlFor="endDate">End date *</label>
        <input id="endDate" type="date" value={endDate} disabled={form.halfDay} onChange={(e) => update('endDate', e.target.value)} aria-invalid={errors.endDate ? true : undefined} />
        {errors.endDate && <span role="alert" className="field-error">{errors.endDate}</span>}
      </div>

      <div className="field">
        <label>
          <input type="checkbox" checked={form.halfDay} onChange={(e) => update('halfDay', e.target.checked)} /> Half day
        </label>
      </div>

      {form.halfDay && (
        <div className="field">
          <label htmlFor="halfDayPeriod">Period *</label>
          <select id="halfDayPeriod" value={form.halfDayPeriod} onChange={(e) => update('halfDayPeriod', e.target.value as HalfDayPeriod | '')} aria-invalid={errors.halfDayPeriod ? true : undefined}>
            <option value="">— Select —</option>
            {periods.map((p) => <option key={p} value={p}>{p}</option>)}
          </select>
          {errors.halfDayPeriod && <span role="alert" className="field-error">{errors.halfDayPeriod}</span>}
        </div>
      )}

      <div className="field">
        <label htmlFor="reason">Reason</label>
        <textarea id="reason" value={form.reason} maxLength={dto.fields.reason.maxLength} onChange={(e) => update('reason', e.target.value)} aria-invalid={errors.reason ? true : undefined} />
        {errors.reason && <span role="alert" className="field-error">{errors.reason}</span>}
      </div>

      <dl className="leave-summary" aria-live="polite">
        <dt>Business days</dt>
        <dd data-testid="business-days">
          {!rangeValid ? '—' : businessDays.isLoading ? '…' : businessDays.isError ? 'unavailable' : requestedDays}
          {businessDays.data?.holidays.length ? ` (holidays: ${businessDays.data.holidays.map((h) => h.holidayName).join(', ')})` : ''}
        </dd>
        <dt>Available balance</dt>
        <dd data-testid="available-balance">{balance ? balance.available : form.leaveTypeId && balances.data ? 'n/a' : '—'}</dd>
      </dl>

      <div className="actions">
        <button type="submit" disabled={busy}>Submit Request</button>
        <button type="button" disabled={busy} onClick={() => { setForm(EMPTY); setErrors({}); }}>Clear</button>
      </div>
    </form>
  );
}
