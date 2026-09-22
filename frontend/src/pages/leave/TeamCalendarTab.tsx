import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../../api/client';
import { formatDate } from '../../app/format';
import { useErrorHandler } from '../../app/useErrorHandler';
import { isoToday } from '../../validation/schema';
import { LeaveStatusBadge } from './LeaveStatusBadge';
import { formatDays, leaveKeys } from './leaveKeys';

function addDays(iso: string, days: number) {
  const d = new Date(`${iso}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

/** HRMS_LEAVE "Team Calendar" block: approved/taken leave of the JWT manager's reports (COMPONENT_MAPPING.md §5). */
export function TeamCalendarTab() {
  const today = isoToday();
  const [from, setFrom] = useState(today);
  const [to, setTo] = useState(addDays(today, 30));
  const { handleError } = useErrorHandler();
  const rangeValid = Boolean(from && to && from <= to);

  const calendar = useQuery({
    queryKey: leaveKeys.calendar(from, to),
    queryFn: () => api.leave.getTeamLeaveCalendar({ from, to }),
    enabled: rangeValid,
  });

  return (
    <div className="team-calendar-tab">
      <h2>Team calendar</h2>
      <div className="filters">
        <div className="field">
          <label htmlFor="calendarFrom">From</label>
          <input id="calendarFrom" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="calendarTo">To</label>
          <input id="calendarTo" type="date" value={to} onChange={(e) => setTo(e.target.value)} aria-invalid={!rangeValid ? true : undefined} />
          {!rangeValid && <span role="alert" className="field-error">Select a valid date range</span>}
        </div>
      </div>
      {calendar.isLoading && <p>Loading calendar…</p>}
      {calendar.isError && <p role="alert">{handleError(calendar.error, { toast: false }).message}</p>}
      {calendar.data && (
        <table aria-label="Team leave calendar">
          <thead>
            <tr><th>Employee</th><th>Type</th><th>From</th><th>To</th><th>Days</th><th>Status</th></tr>
          </thead>
          <tbody>
            {calendar.data.length === 0 && <tr><td colSpan={6}>No team leave in this range.</td></tr>}
            {calendar.data.map((e) => (
              <tr key={e.requestId}>
                <td>{e.empName}</td>
                <td>{e.leaveTypeName}</td>
                <td>{formatDate(e.startDate)}</td>
                <td>{formatDate(e.endDate)}</td>
                <td>{formatDays(e.totalDays, e.halfDay, e.halfDayPeriod)}</td>
                <td><LeaveStatusBadge status={e.status} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
