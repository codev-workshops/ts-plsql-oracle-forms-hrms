import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { api } from '../../api/client';
import { formatDate } from '../../app/format';
import { useErrorHandler } from '../../app/useErrorHandler';
import { fieldErrors as zodFieldErrors, zodFor } from '../../validation/schema';
import { LeaveStatusBadge } from './LeaveStatusBadge';
import { addDaysIso, describeHalfDay, formatDays, isoToday } from './leaveFormat';
import { teamCalendarQueryKey } from './queryKeys';

const rangeSchema = zodFor('BusinessDaysQuery');

/** `PKG_LEAVE.get_team_calendar`: approved leave of the caller's direct reports in a date range. */
export function TeamCalendarTab() {
  const today = isoToday();
  const [draft, setDraft] = useState({ from: today, to: addDaysIso(today, 30) });
  const [range, setRange] = useState(draft);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const { handleError } = useErrorHandler();
  const calendar = useQuery({
    queryKey: [...teamCalendarQueryKey, range],
    queryFn: () => api.leave.getTeamLeaveCalendar(range),
    retry: false,
  });
  const apply = () => {
    const parsed = rangeSchema.safeParse({ start: draft.from, end: draft.to });
    if (!parsed.success) {
      const e = zodFieldErrors(parsed.error);
      setErrors({ from: e.start, to: e.end });
      return;
    }
    setErrors({});
    setRange(draft);
  };
  useEffect(() => {
    if (!calendar.isError) return;
    const handled = handleError(calendar.error, { fieldNames: ['from', 'to'], toast: false });
    setErrors({ ...handled.fieldErrors, server: handled.message });
  }, [calendar.isError, calendar.error, handleError]);
  return (
    <section aria-labelledby="team-calendar-title">
      <h2 id="team-calendar-title">Team Calendar</h2>
      <div className="filters">
        <div className="field">
          <label htmlFor="calendarFrom">From</label>
          <input id="calendarFrom" type="date" value={draft.from} onChange={(e) => setDraft((old) => ({ ...old, from: e.target.value }))} aria-invalid={errors.from ? true : undefined} />
          {errors.from && <span role="alert" className="field-error">{errors.from}</span>}
        </div>
        <div className="field">
          <label htmlFor="calendarTo">To</label>
          <input id="calendarTo" type="date" value={draft.to} onChange={(e) => setDraft((old) => ({ ...old, to: e.target.value }))} aria-invalid={errors.to ? true : undefined} />
          {errors.to && <span role="alert" className="field-error">{errors.to}</span>}
        </div>
        <button type="button" onClick={apply}>Apply</button>
      </div>
      {calendar.isPending ? <p role="status">Loading…</p> : calendar.isError ? <p role="alert">{errors.server ?? 'Could not load the team calendar.'}</p> : calendar.data.length ? (
        <table className="grid" aria-label="Team leave calendar">
          <thead><tr><th>Employee</th><th>Leave type</th><th>Start</th><th>End</th><th>Days</th><th>Duration</th><th>Status</th></tr></thead>
          <tbody>
            {calendar.data.map((r) => (
              <tr key={r.requestId}>
                <td>{r.empName}</td>
                <td>{r.leaveTypeName}</td>
                <td>{formatDate(r.startDate)}</td>
                <td>{formatDate(r.endDate)}</td>
                <td>{formatDays(r.totalDays)}</td>
                <td>{describeHalfDay(r.halfDay, r.halfDayPeriod)}</td>
                <td><LeaveStatusBadge status={r.status} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : <p>No approved leave in this range</p>}
    </section>
  );
}
