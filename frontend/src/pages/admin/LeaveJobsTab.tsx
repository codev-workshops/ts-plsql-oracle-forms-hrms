import { useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/client';
import type { AccrualRunRequest, BatchRunResult, CarryoverRunRequest } from '../../api/types';
import { useAuth } from '../../app/AuthContext';
import { normaliseError, useErrorHandler } from '../../app/useErrorHandler';
import { SchemaField, emptyValues, parseWith, type FormValues } from '../shared/schemaForm';

/** Poll cadence for `GET /api/admin/leave/jobs/{jobId}` while a job is RUNNING. */
export const JOB_POLL_MS = 2000;

/**
 * `HRMS_ADMIN › BTN_RUN_ACCRUAL / BTN_RUN_CARRYOVER` → `POST /api/admin/leave/accrual-run` and
 * `POST /api/admin/leave/carryover-run` (PKG_LEAVE_MGMT batch jobs, `-20702` when one is already
 * running). `requestedBy` is the JWT subject – nothing identifying the caller is sent. Needs `LEAVE:ADMIN`.
 */
export function LeaveJobsTab() {
  const { hasAuthority } = useAuth();
  const canRun = hasAuthority('LEAVE:ADMIN');
  const { handleError } = useErrorHandler();
  const [accrual, setAccrual] = useState<FormValues>(() => emptyValues('AccrualRunRequest'));
  const [carryover, setCarryover] = useState<FormValues>(() => emptyValues('CarryoverRunRequest', { year: new Date().getUTCFullYear() - 1 }));
  const [accrualErrors, setAccrualErrors] = useState<Record<string, string>>({});
  const [carryoverErrors, setCarryoverErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState<'ACCRUAL' | 'CARRYOVER' | null>(null);
  const [results, setResults] = useState<BatchRunResult[]>([]);
  const [lookupId, setLookupId] = useState('');
  const [trackedJobId, setTrackedJobId] = useState<string | null>(null);

  const tracked = useQuery({
    queryKey: ['admin', 'leave-jobs', trackedJobId],
    queryFn: () => api.admin.getLeaveJob(trackedJobId as string),
    enabled: trackedJobId !== null,
    refetchInterval: (q) => (q.state.data?.status === 'RUNNING' ? JOB_POLL_MS : false),
  });

  const runAccrual = async (event: FormEvent) => {
    event.preventDefault();
    const parsed = parseWith<AccrualRunRequest>('AccrualRunRequest', accrual);
    setAccrualErrors(parsed.errors);
    if (!parsed.data) return;
    setBusy('ACCRUAL');
    try {
      const r = await api.admin.runLeaveAccrual(parsed.data);
      setResults((prev) => [r, ...prev]);
      setTrackedJobId(r.jobId);
    } catch (error) {
      setAccrualErrors(handleError(error, { fieldNames: ['accrualDate'] }).fieldErrors);
    } finally {
      setBusy(null);
    }
  };

  const runCarryover = async (event: FormEvent) => {
    event.preventDefault();
    const parsed = parseWith<CarryoverRunRequest>('CarryoverRunRequest', carryover);
    setCarryoverErrors(parsed.errors);
    if (!parsed.data) return;
    setBusy('CARRYOVER');
    try {
      const r = await api.admin.runLeaveCarryover(parsed.data);
      setResults((prev) => [r, ...prev]);
      setTrackedJobId(r.jobId);
    } catch (error) {
      setCarryoverErrors(handleError(error, { fieldNames: ['year'] }).fieldErrors);
    } finally {
      setBusy(null);
    }
  };

  const trackedError = tracked.isError ? normaliseError(tracked.error).apiError : null;

  const lookup = (event: FormEvent) => {
    event.preventDefault();
    if (lookupId.trim()) setTrackedJobId(lookupId.trim());
  };

  return (
    <div data-testid="admin-leave-jobs" className="leave-jobs">
      <p className="muted">
        Rebuilds <code>HRMS_ADMIN › BTN_RUN_ACCRUAL / BTN_RUN_CARRYOVER</code>
      </p>
      {!canRun && <p role="status">Running batch jobs requires the LEAVE:ADMIN authority.</p>}
      <div className="cards">
        <form className="card" onSubmit={runAccrual} noValidate aria-labelledby="accrual-title">
          <h3 id="accrual-title">Leave accrual</h3>
          <SchemaField dto="AccrualRunRequest" field={{ name: 'accrualDate', label: 'Accrual date', hint: 'Defaults to today' }} values={accrual} errors={accrualErrors} onChange={(n, v) => setAccrual((p) => ({ ...p, [n]: v }))} idPrefix="accrual-" />
          <div className="actions">
            <button type="submit" disabled={!canRun || busy !== null}>
              {busy === 'ACCRUAL' ? 'Starting…' : 'Run accrual'}
            </button>
          </div>
        </form>
        <form className="card" onSubmit={runCarryover} noValidate aria-labelledby="carryover-title">
          <h3 id="carryover-title">Year-end carryover</h3>
          <SchemaField dto="CarryoverRunRequest" field={{ name: 'year', label: 'Year to close' }} values={carryover} errors={carryoverErrors} onChange={(n, v) => setCarryover((p) => ({ ...p, [n]: v }))} idPrefix="carryover-" />
          <div className="actions">
            <button type="submit" disabled={!canRun || busy !== null}>
              {busy === 'CARRYOVER' ? 'Starting…' : 'Run carryover'}
            </button>
          </div>
        </form>
        <form className="card" onSubmit={lookup} aria-labelledby="job-lookup-title">
          <h3 id="job-lookup-title">Job status</h3>
          <div className="field">
            <label htmlFor="jobLookup">Job id</label>
            <input id="jobLookup" value={lookupId} onChange={(e) => setLookupId(e.target.value)} placeholder="UUID" />
          </div>
          <div className="actions">
            <button type="submit" disabled={!lookupId.trim()}>
              Look up
            </button>
          </div>
        </form>
      </div>

      {trackedJobId && (
        <section aria-live="polite" data-testid="tracked-job">
          <h3>Job {trackedJobId}</h3>
          {tracked.isPending && <p role="status">Loading job status…</p>}
          {tracked.isError && (
            <p role="alert" className="field-error">
              Could not load job {trackedJobId}.{trackedError ? ` ${trackedError.code}: ${trackedError.message}` : ''}
            </p>
          )}
          {tracked.data && <JobRow job={tracked.data} />}
        </section>
      )}

      {results.length > 0 && (
        <table className="grid" aria-label="Batch runs started this session">
          <thead>
            <tr>
              <th scope="col">Job</th>
              <th scope="col">Type</th>
              <th scope="col">Status</th>
              <th scope="col">Processed</th>
              <th scope="col">Skipped</th>
              <th scope="col">Failed</th>
              <th scope="col">Started</th>
              <th scope="col">By</th>
            </tr>
          </thead>
          <tbody>
            {results.map((r) => (
              <tr key={r.jobId}>
                <td>
                  <button type="button" className="link" onClick={() => setTrackedJobId(r.jobId)}>
                    {r.jobId}
                  </button>
                </td>
                <td>{r.jobType}</td>
                <td>
                  <span className={`badge badge-${r.status}`}>{r.status}</span>
                </td>
                <td>{r.processed}</td>
                <td>{r.skipped}</td>
                <td>{r.failed}</td>
                <td>{r.startedAt}</td>
                <td>{r.startedBy}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

function JobRow({ job }: { job: BatchRunResult }) {
  return (
    <dl className="kv" data-testid="job-detail">
      <dt>Type</dt>
      <dd>{job.jobType}</dd>
      <dt>Status</dt>
      <dd>
        <span className={`badge badge-${job.status}`}>{job.status}</span>
      </dd>
      <dt>Processed / skipped / failed</dt>
      <dd>
        {job.processed} / {job.skipped} / {job.failed}
      </dd>
      <dt>Started</dt>
      <dd>
        {job.startedAt} by {job.startedBy}
      </dd>
      <dt>Finished</dt>
      <dd>{job.finishedAt ?? '—'}</dd>
      {job.message && (
        <>
          <dt>Message</dt>
          <dd>{job.message}</dd>
        </>
      )}
    </dl>
  );
}
