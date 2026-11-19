import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { ApiError } from '../../api/client';
import { leaveApi, type LeaveBalance, type LeaveRequest, type LeaveType } from './api';

type Tab = 'requests' | 'new' | 'approvals' | 'team';

const TABS: { id: Tab; label: string }[] = [
  { id: 'requests', label: 'My requests' },
  { id: 'new', label: 'Request leave' },
  { id: 'approvals', label: 'Approvals' },
  { id: 'team', label: 'Team calendar' },
];

function message(err: unknown): string {
  return err instanceof ApiError ? `${err.message} (${err.code})` : 'Unexpected error';
}

function isoDate(d: Date): string {
  return d.toISOString().slice(0, 10);
}

export function LeavePage() {
  const [tab, setTab] = useState<Tab>('requests');
  const [flash, setFlash] = useState<string | null>(null);
  return (
    <section>
      <h1>Leave</h1>
      <div className="tabs" role="tablist">
        {TABS.map((t) => (
          <button key={t.id} role="tab" aria-selected={tab === t.id} onClick={() => setTab(t.id)} type="button">
            {t.label}
          </button>
        ))}
      </div>
      {flash && <p className="success" role="status">{flash}</p>}
      {tab === 'requests' && <MyRequests />}
      {tab === 'new' && (
        <RequestForm
          onSubmitted={(r) => {
            setFlash(`Request #${r.requestId} submitted (${r.status}, ${r.totalDays} day(s))`);
            setTab('requests');
          }}
        />
      )}
      {tab === 'approvals' && <Approvals />}
      {tab === 'team' && <TeamCalendar />}
    </section>
  );
}

function Balances() {
  const [balances, setBalances] = useState<LeaveBalance[]>([]);
  useEffect(() => {
    leaveApi.balances().then(setBalances).catch(() => setBalances([]));
  }, []);
  return (
    <table aria-label="Balances">
      <thead>
        <tr><th>Type</th><th>Accrued</th><th>Used</th><th>Pending</th><th>Available</th></tr>
      </thead>
      <tbody>
        {balances.map((b) => (
          <tr key={b.leaveTypeId}>
            <td>{b.leaveTypeName}</td><td>{b.openingBalance + b.accrued}</td><td>{b.used}</td>
            <td>{b.pending}</td><td>{b.available}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function MyRequests() {
  const [requests, setRequests] = useState<LeaveRequest[]>([]);
  const [error, setError] = useState<string | null>(null);
  const load = useCallback(() => {
    leaveApi.myRequests().then(setRequests).catch((e) => setError(message(e)));
  }, []);
  useEffect(load, [load]);

  async function cancel(r: LeaveRequest) {
    const reason = window.prompt('Reason for cancelling?');
    if (!reason) return;
    try {
      await leaveApi.cancel(r.requestId, reason);
      load();
    } catch (e) {
      setError(message(e));
    }
  }

  return (
    <>
      <h2>Balances</h2>
      <Balances key={requests.length + requests.filter((r) => r.status === 'CANCELLED').length} />
      <h2>My requests</h2>
      {error && <p role="alert" className="error">{error}</p>}
      <table aria-label="My requests">
        <thead>
          <tr><th>#</th><th>Type</th><th>From</th><th>To</th><th>Days</th><th>Status</th><th /></tr>
        </thead>
        <tbody>
          {requests.map((r) => (
            <tr key={r.requestId}>
              <td>{r.requestId}</td><td>{r.leaveTypeName}</td><td>{r.startDate}</td><td>{r.endDate}</td>
              <td>{r.totalDays}</td><td>{r.status}</td>
              <td>
                {(r.status === 'PENDING' || r.status === 'APPROVED') && (
                  <button type="button" onClick={() => void cancel(r)}>Cancel</button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {requests.length === 0 && <p>No leave requests yet.</p>}
    </>
  );
}

function RequestForm({ onSubmitted }: { onSubmitted: (r: LeaveRequest) => void }) {
  const [types, setTypes] = useState<LeaveType[]>([]);
  const [leaveTypeId, setLeaveTypeId] = useState('');
  const [startDate, setStartDate] = useState('');
  const [endDate, setEndDate] = useState('');
  const [halfDay, setHalfDay] = useState(false);
  const [halfDayPeriod, setHalfDayPeriod] = useState<'AM' | 'PM'>('AM');
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    leaveApi.types().then(setTypes).catch((e) => setError(message(e)));
  }, []);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    const end = halfDay ? startDate : endDate;
    if (end < startDate) {
      setError('Start date must be before or equal to end date');
      return;
    }
    try {
      const created = await leaveApi.submit({
        leaveTypeId: Number(leaveTypeId),
        startDate,
        endDate: end,
        halfDay,
        halfDayPeriod: halfDay ? halfDayPeriod : undefined,
        reason: reason || undefined,
      });
      onSubmitted(created);
    } catch (err) {
      setError(message(err));
    }
  }

  return (
    <form className="card" onSubmit={submit} aria-label="Request leave">
      <label>
        Leave type
        <select value={leaveTypeId} onChange={(e) => setLeaveTypeId(e.target.value)} required>
          <option value="">Select…</option>
          {types.map((t) => (
            <option key={t.leaveTypeId} value={t.leaveTypeId}>{t.name}</option>
          ))}
        </select>
      </label>
      <div className="row">
        <label>
          Start date
          <input type="date" value={startDate} onChange={(e) => setStartDate(e.target.value)} required />
        </label>
        {!halfDay && (
          <label>
            End date
            <input type="date" value={endDate} onChange={(e) => setEndDate(e.target.value)} required />
          </label>
        )}
      </div>
      <label>
        <span>
          <input type="checkbox" checked={halfDay} onChange={(e) => setHalfDay(e.target.checked)} /> Half day
        </span>
      </label>
      {halfDay && (
        <label>
          Period
          <select value={halfDayPeriod} onChange={(e) => setHalfDayPeriod(e.target.value as 'AM' | 'PM')}>
            <option value="AM">AM</option>
            <option value="PM">PM</option>
          </select>
        </label>
      )}
      <label>
        Reason
        <textarea value={reason} onChange={(e) => setReason(e.target.value)} maxLength={4000} />
      </label>
      {error && <p role="alert" className="error">{error}</p>}
      <button type="submit">Submit request</button>
    </form>
  );
}

function Approvals() {
  const [pending, setPending] = useState<LeaveRequest[]>([]);
  const [error, setError] = useState<string | null>(null);
  const load = useCallback(() => {
    leaveApi.approvals().then(setPending).catch((e) => setError(message(e)));
  }, []);
  useEffect(load, [load]);

  async function decide(r: LeaveRequest, approve: boolean) {
    try {
      if (approve) {
        await leaveApi.approve(r.requestId);
      } else {
        const comments = window.prompt('Reason for rejection?');
        if (!comments) return;
        await leaveApi.reject(r.requestId, comments);
      }
      load();
    } catch (e) {
      setError(message(e));
    }
  }

  return (
    <>
      {error && <p role="alert" className="error">{error}</p>}
      <table aria-label="Pending approvals">
        <thead>
          <tr><th>#</th><th>Employee</th><th>Type</th><th>From</th><th>To</th><th>Days</th><th /></tr>
        </thead>
        <tbody>
          {pending.map((r) => (
            <tr key={r.requestId}>
              <td>{r.requestId}</td><td>{r.employeeName}</td><td>{r.leaveTypeName}</td><td>{r.startDate}</td>
              <td>{r.endDate}</td><td>{r.totalDays}</td>
              <td>
                <button type="button" onClick={() => void decide(r, true)}>Approve</button>{' '}
                <button type="button" onClick={() => void decide(r, false)}>Reject</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {pending.length === 0 && <p>No requests waiting for your approval.</p>}
    </>
  );
}

function TeamCalendar() {
  const today = new Date();
  const [startDate, setStartDate] = useState(isoDate(new Date(today.getFullYear(), today.getMonth(), 1)));
  const [endDate, setEndDate] = useState(isoDate(new Date(today.getFullYear(), today.getMonth() + 2, 0)));
  const [rows, setRows] = useState<LeaveRequest[]>([]);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    leaveApi.teamCalendar(startDate, endDate).then(setRows).catch((e) => setError(message(e)));
  }, [startDate, endDate]);
  return (
    <>
      <div className="row">
        <label>From <input type="date" value={startDate} onChange={(e) => setStartDate(e.target.value)} /></label>
        <label>To <input type="date" value={endDate} onChange={(e) => setEndDate(e.target.value)} /></label>
      </div>
      {error && <p role="alert" className="error">{error}</p>}
      <table aria-label="Team calendar">
        <thead>
          <tr><th>Employee</th><th>Type</th><th>From</th><th>To</th><th>Days</th></tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.requestId}>
              <td>{r.employeeName}</td><td>{r.leaveTypeName}</td><td>{r.startDate}</td><td>{r.endDate}</td>
              <td>{r.totalDays}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {rows.length === 0 && <p>No approved team leave in this range.</p>}
    </>
  );
}
