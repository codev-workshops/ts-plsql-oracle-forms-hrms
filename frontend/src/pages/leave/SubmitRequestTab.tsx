import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/client';
import type { HalfDayPeriod, LeaveRequestCreateRequest, LeaveTypeRef } from '../../api/types';
import { formatDate } from '../../app/format';
import { useToast } from '../../app/ToastContext';
import { useErrorHandler } from '../../app/useErrorHandler';
import { ReferenceDropdown, referenceQueryKey } from '../../components/ReferenceDropdown';
import { fieldErrors as zodFieldErrors, getDto, zodFor } from '../../validation/schema';
import { formatDays } from './leaveFormat';
import { balancesQueryKey, myRequestsQueryKey } from './queryKeys';

const schema = zodFor('LeaveRequestCreateRequest');
const dto = getDto('LeaveRequestCreateRequest').fields;
const periods = (dto.halfDayPeriod.values ?? []) as HalfDayPeriod[];
const fields = ['leaveTypeId', 'startDate', 'endDate', 'halfDay', 'halfDayPeriod', 'reason'] as const;

interface FormState {
  leaveTypeId: string | null;
  startDate: string;
  endDate: string;
  halfDay: boolean;
  halfDayPeriod: HalfDayPeriod | '';
  reason: string;
}

const EMPTY: FormState = { leaveTypeId: null, startDate: '', endDate: '', halfDay: false, halfDayPeriod: '', reason: '' };
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

/**
 * `BTN_SUBMIT` (HRMS_LEAVE.xml) → `POST /api/leave/requests`. Live business-day count comes
 * from `GET /api/leave/business-days` (BUG-05 observed-holiday semantics live server-side).
 */
export function SubmitRequestTab() {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const [form, setForm] = useState<FormState>(EMPTY);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const { handleError } = useErrorHandler();
  const { push } = useToast();

  const set = <K extends keyof FormState>(key: K, value: FormState[K]) => setForm((old) => ({ ...old, [key]: value }));

  const datesValid = ISO_DATE.test(form.startDate) && ISO_DATE.test(form.endDate) && form.startDate <= form.endDate;
  const businessDays = useQuery({
    queryKey: ['leave', 'business-days', { start: form.startDate, end: form.endDate }],
    queryFn: () => api.leave.getBusinessDays({ start: form.startDate, end: form.endDate }),
    enabled: datesValid,
  });
  const balances = useQuery({ queryKey: balancesQueryKey, queryFn: () => api.leave.getMyLeaveBalances() });
  // Same key/queryFn as the ReferenceDropdown so the two share one request.
  const leaveTypes = useQuery<LeaveTypeRef[]>({
    queryKey: referenceQueryKey('leave-types', { active: true }),
    queryFn: () => api.reference.listLeaveTypes({ active: true }),
    staleTime: 5 * 60 * 1000,
  });

  const leaveTypeId = form.leaveTypeId ? Number(form.leaveTypeId) : null;
  const type = leaveTypes.data?.find((t) => t.leaveTypeId === leaveTypeId);
  const currentYear = new Date().getFullYear();
  const balance = balances.data?.find((b) => b.leaveTypeId === leaveTypeId && b.calendarYear === currentYear);
  const requested = form.halfDay ? 0.5 : businessDays.data?.businessDays;
  const available = balance?.available ?? (type ? 0 : undefined);
  const insufficient = type?.accrual && requested !== undefined && available !== undefined && available < requested;

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const candidate = {
      leaveTypeId: form.leaveTypeId ?? '',
      startDate: form.startDate,
      endDate: form.endDate,
      halfDay: form.halfDay,
      halfDayPeriod: form.halfDay && form.halfDayPeriod ? form.halfDayPeriod : undefined,
      reason: form.reason || undefined,
    };
    const parsed = schema.safeParse(candidate);
    if (!parsed.success) {
      setErrors(zodFieldErrors(parsed.error));
      return;
    }
    setErrors({});
    setBusy(true);
    const body: LeaveRequestCreateRequest = {
      leaveTypeId: Number(form.leaveTypeId),
      startDate: form.startDate,
      endDate: form.endDate,
      halfDay: form.halfDay,
      halfDayPeriod: form.halfDay ? (form.halfDayPeriod as HalfDayPeriod) : null,
      reason: form.reason.trim() || null,
    };
    try {
      const created = await api.leave.submitLeaveRequest(body);
      push({ kind: 'success', message: created.status === 'APPROVED' ? 'Leave request submitted and auto-approved' : 'Leave request submitted for approval' });
      void qc.invalidateQueries({ queryKey: myRequestsQueryKey });
      void qc.invalidateQueries({ queryKey: balancesQueryKey });
      setForm(EMPTY);
      navigate('/leave');
    } catch (error) {
      const handled = handleError(error, { fieldNames: fields });
      setErrors(handled.fieldErrors);
    } finally {
      setBusy(false);
    }
  };

  return (
    <section aria-labelledby="submit-request-title">
      <h2 id="submit-request-title">Submit Request</h2>
      <form onSubmit={submit} noValidate className="leave-form">
        <ReferenceDropdown source="leave-types" label="Leave type" name="leaveTypeId" required value={form.leaveTypeId} onChange={(v) => set('leaveTypeId', v)} error={errors.leaveTypeId} />
        {type && (
          <ul className="hint" aria-label="Leave type policy">
            {!type.requiresApproval && <li>This leave type is auto-approved on submission.</li>}
            {type.requiresDocument && <li>Supporting documentation is required for this leave type.</li>}
            {type.minTenureDays > 0 && <li>Requires at least {type.minTenureDays} days of tenure.</li>}
          </ul>
        )}
        <div className="field">
          <label htmlFor="startDate">Start date</label>
          <input id="startDate" type="date" value={form.startDate} onChange={(e) => setForm((old) => ({ ...old, startDate: e.target.value, endDate: old.halfDay ? e.target.value : old.endDate }))} aria-invalid={errors.startDate ? true : undefined} />
          {errors.startDate && <span role="alert" className="field-error">{errors.startDate}</span>}
        </div>
        <div className="field">
          <label htmlFor="endDate">End date</label>
          <input id="endDate" type="date" value={form.endDate} disabled={form.halfDay} onChange={(e) => set('endDate', e.target.value)} aria-invalid={errors.endDate ? true : undefined} />
          {errors.endDate && <span role="alert" className="field-error">{errors.endDate}</span>}
        </div>
        <div className="field field-inline">
          <input id="halfDay" type="checkbox" checked={form.halfDay} onChange={(e) => setForm((old) => ({ ...old, halfDay: e.target.checked, endDate: e.target.checked ? old.startDate : old.endDate, halfDayPeriod: e.target.checked ? old.halfDayPeriod : '' }))} />
          <label htmlFor="halfDay">Half day</label>
        </div>
        {form.halfDay && (
          <div className="field">
            <label htmlFor="halfDayPeriod">Half-day period</label>
            <select id="halfDayPeriod" value={form.halfDayPeriod} onChange={(e) => set('halfDayPeriod', e.target.value as HalfDayPeriod | '')} aria-invalid={errors.halfDayPeriod ? true : undefined}>
              <option value="">— Select —</option>
              {periods.map((p) => <option key={p} value={p}>{p}</option>)}
            </select>
            {errors.halfDayPeriod && <span role="alert" className="field-error">{errors.halfDayPeriod}</span>}
          </div>
        )}
        <div className="field">
          <label htmlFor="reason">Reason</label>
          <textarea id="reason" value={form.reason} maxLength={dto.reason.maxLength} onChange={(e) => set('reason', e.target.value)} aria-invalid={errors.reason ? true : undefined} />
          {errors.reason && <span role="alert" className="field-error">{errors.reason}</span>}
        </div>

        <dl className="summary" aria-label="Request summary">
          <dt>Business days</dt>
          <dd data-testid="business-days">
            {form.halfDay ? '0.5' : !datesValid ? '—' : businessDays.isPending ? 'Calculating…' : businessDays.isError ? 'Unavailable' : formatDays(businessDays.data.businessDays)}
          </dd>
          {businessDays.data && businessDays.data.holidays.length > 0 && !form.halfDay && (
            <>
              <dt>Holidays excluded</dt>
              <dd>{businessDays.data.holidays.map((h) => `${h.holidayName} (${formatDate(h.observedDate)})`).join(', ')}</dd>
            </>
          )}
          <dt>Available balance</dt>
          <dd data-testid="available-balance">
            {!type ? '—' : balances.isPending ? 'Loading…' : available === undefined ? '—' : `${formatDays(available)} day(s) of ${type.leaveTypeName}`}
          </dd>
        </dl>
        {insufficient && <p role="status" className="warning">Requested days exceed the available balance; the server will reject this request.</p>}
        <div className="actions">
          <button type="submit" disabled={busy}>Submit Request</button>
          <button type="button" onClick={() => { setForm(EMPTY); setErrors({}); }} disabled={busy}>Clear</button>
        </div>
      </form>
    </section>
  );
}
