import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api } from '../../api/client';
import type { PayPeriod, PayrollRun, PayrollRunApproval, PayrollRunStatus, RunStatus } from '../../api/types';
import { formatDate, formatDateTime, formatMoney } from '../../app/format';
import { useErrorHandler } from '../../app/useErrorHandler';
import { useToast } from '../../app/ToastContext';
import { getDto } from '../../validation/schema';
import { CreateRunDialog } from './CreateRunDialog';
import { ReverseRunDialog } from './ReverseRunDialog';
import { CALCULATION_POLL_MS, errorLabel, humanize, payPeriodsKey, payrollRunStatusKey, payrollRunsKey, saveTextFile, usePayrollWrite } from './payrollShared';

const STATUSES = getDto('PayrollRunListQuery').fields.status.values as RunStatus[];
const CALCULABLE: RunStatus[] = ['PENDING', 'CALCULATED', 'ERROR'];
const REVERSIBLE: RunStatus[] = ['CALCULATED', 'APPROVED', 'PAID', 'ERROR'];

/** `PAYROLL_RUNS` block (HRMS_PAYROLL.xml) → `GET /api/payroll/periods/{periodId}/runs` + the four run buttons. */
export function PayrollRunsTab() {
  const periodId = Number(useParams().periodId);
  const { canApprove } = usePayrollWrite();
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<RunStatus | ''>('');
  const [creating, setCreating] = useState(false);
  const [reversing, setReversing] = useState<PayrollRun | null>(null);
  const [approving, setApproving] = useState<PayrollRun | null>(null);
  const [approval, setApproval] = useState<PayrollRunApproval | null>(null);

  const params = status ? { status } : {};
  const period = useQuery({
    queryKey: payPeriodsKey({ size: 100 }),
    queryFn: () => api.payroll.listPayPeriods({ size: 100 }),
    select: (page) => page.content.find((p) => p.periodId === periodId),
  });
  const runs = useQuery({ queryKey: payrollRunsKey(periodId, params), queryFn: () => api.payroll.listPayrollRuns(periodId, params), placeholderData: (prev) => prev });

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['payroll', 'runs', periodId] });
    void queryClient.invalidateQueries({ queryKey: ['payroll', 'periods'] });
  };

  const p: PayPeriod | undefined = period.data;
  return (
    <div data-testid="payroll-runs-tab">
      <div className="toolbar">
        <Link to="/payroll">← Pay periods</Link>
        <h2 style={{ margin: 0 }} data-testid="runs-period-name">{p ? p.periodName : `Period ${periodId}`}</h2>
        {p && <span className={`badge badge-${p.status}`}>{humanize(p.status)}</span>}
        <label htmlFor="runStatus">Status</label>
        <select id="runStatus" value={status} onChange={(e) => setStatus(e.target.value as RunStatus | '')}>
          <option value="">All</option>
          {STATUSES.map((s) => <option key={s} value={s}>{humanize(s)}</option>)}
        </select>
        {canApprove && p && p.status !== 'CLOSED' && (
          <button type="button" onClick={() => setCreating(true)} data-testid="create-run">Create run</button>
        )}
      </div>

      {runs.isPending && <p role="status">Loading payroll runs…</p>}
      {runs.isError && <p role="alert" className="field-error">Could not load payroll runs.</p>}
      {runs.data && (
        <table className="grid" aria-label="Payroll runs">
          <thead>
            <tr>
              <th>Run</th>
              <th>Type</th>
              <th>Run date</th>
              <th>Status</th>
              <th>Employees</th>
              <th>Errors</th>
              <th>Gross</th>
              <th>Deductions</th>
              <th>Net</th>
              <th>Approved</th>
              <th><span className="sr-only">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            {runs.data.length === 0 && <tr><td colSpan={11}>No payroll runs for this period.</td></tr>}
            {runs.data.map((run) => (
              <RunRow
                key={run.runId}
                run={run}
                canApprove={canApprove}
                onChanged={refresh}
                onApprove={() => setApproving(run)}
                onReverse={() => setReversing(run)}
              />
            ))}
          </tbody>
        </table>
      )}

      {creating && p && (
        <CreateRunDialog
          period={p}
          onClose={() => setCreating(false)}
          onCreated={() => {
            setCreating(false);
            refresh();
          }}
        />
      )}
      {reversing && (
        <ReverseRunDialog
          run={reversing}
          onClose={() => setReversing(null)}
          onReversed={() => {
            setReversing(null);
            refresh();
          }}
        />
      )}
      {approving && (
        <ApproveRunDialog
          run={approving}
          onClose={() => setApproving(null)}
          onApproved={(result) => {
            setApproving(null);
            setApproval(result);
            refresh();
          }}
        />
      )}
      {approval && <ApprovalResultDialog approval={approval} onClose={() => setApproval(null)} />}
    </div>
  );
}

interface RowProps {
  run: PayrollRun;
  canApprove: boolean;
  onChanged: () => void;
  onApprove: () => void;
  onReverse: () => void;
}

function RunRow({ run, canApprove, onChanged, onApprove, onReverse }: RowProps) {
  const navigate = useNavigate();
  const { handleError } = useErrorHandler();
  const { push } = useToast();
  const queryClient = useQueryClient();
  const [includeBank, setIncludeBank] = useState(false);

  // `POST …/calculate` answers 202 with the initial status; poll `GET …/status` every 2 s until it leaves CALCULATING.
  const progress = useQuery({
    queryKey: payrollRunStatusKey(run.runId),
    queryFn: () => api.payroll.getPayrollRunStatus(run.runId),
    enabled: run.status === 'CALCULATING',
    refetchInterval: (q) => (q.state.data?.status === 'CALCULATING' || !q.state.data ? CALCULATION_POLL_MS : false),
  });
  const live: PayrollRunStatus | undefined = progress.data;
  const calculating = run.status === 'CALCULATING' && (!live || live.status === 'CALCULATING');

  useEffect(() => {
    if (run.status === 'CALCULATING' && live && live.status !== 'CALCULATING') onChanged();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [live?.status, run.status]);

  const calculate = useMutation({
    mutationFn: () => api.payroll.calculatePayrollRun(run.runId),
    onSuccess: (initial) => {
      queryClient.setQueryData(payrollRunStatusKey(run.runId), initial);
      onChanged();
    },
    onError: (error) => handleError(error),
  });

  const download = useMutation({
    mutationFn: () => api.payroll.downloadPayrollRegister(run.runId, includeBank ? { includeBank: true } : {}),
    onSuccess: (file) => {
      saveTextFile(file.filename, file.csv);
      push({ kind: 'success', message: `Downloaded ${file.filename}` });
    },
    onError: (error) => handleError(error),
  });

  const errorCount = live && run.status === 'CALCULATING' ? live.errorCount : run.errorCount;
  const hasDetails = !['PENDING', 'CALCULATING'].includes(run.status);

  return (
    <tr data-testid={`run-row-${run.runId}`}>
      <td>#{run.runId}</td>
      <td>{humanize(run.runType)}</td>
      <td>{formatDate(run.runDate)}</td>
      <td>
        <span className={`badge badge-${run.status}`}>{humanize(run.status)}</span>
        {calculating && (
          <span role="status" className="hint" data-testid={`run-progress-${run.runId}`}>
            {' '}
            <progress value={live?.total ? live.processed : undefined} max={live?.total ?? undefined} aria-label="Calculation progress" />{' '}
            {live?.total ? `${live.processed} / ${live.total}` : 'Starting…'}
          </span>
        )}
        {run.status === 'ERROR' && live?.failureMessage && <span className="field-error"> {live.failureMessage}</span>}
      </td>
      <td>{run.employeeCount}</td>
      <td>
        {errorCount > 0 ? (
          <span className="badge badge-ERROR" data-testid={`run-errors-${run.runId}`} title="Employees skipped with an ERROR detail row">
            {errorCount} {errorCount === 1 ? 'error' : 'errors'}
          </span>
        ) : (
          '—'
        )}
      </td>
      <td>{formatMoney(run.totalGross)}</td>
      <td>{formatMoney(run.totalDeductions)}</td>
      <td>{formatMoney(run.totalNet)}</td>
      <td>{run.approvedBy ? `${run.approvedBy} · ${formatDateTime(run.approvedDate)}` : '—'}</td>
      <td className="actions">
        {hasDetails && (
          <button type="button" onClick={() => navigate(`/payroll/runs/${run.runId}/details`)} data-testid={`run-details-${run.runId}`}>Details</button>
        )}
        {canApprove && CALCULABLE.includes(run.status) && (
          <button type="button" onClick={() => calculate.mutate()} disabled={calculate.isPending} data-testid={`run-calculate-${run.runId}`}>
            {run.status === 'PENDING' ? 'Calculate' : 'Recalculate'}
          </button>
        )}
        {canApprove && run.status === 'CALCULATED' && (
          <button type="button" onClick={onApprove} data-testid={`run-approve-${run.runId}`}>Approve</button>
        )}
        {canApprove && REVERSIBLE.includes(run.status) && (
          <button type="button" onClick={onReverse} data-testid={`run-reverse-${run.runId}`}>Reverse</button>
        )}
        {hasDetails && run.status !== 'REVERSED' && (
          <>
            <button type="button" onClick={() => download.mutate()} disabled={download.isPending} data-testid={`run-register-${run.runId}`}>Register CSV</button>
            {canApprove && (
              <label className="hint">
                <input type="checkbox" checked={includeBank} onChange={(e) => setIncludeBank(e.target.checked)} /> include bank (masked)
              </label>
            )}
          </>
        )}
      </td>
    </tr>
  );
}

function ApproveRunDialog({ run, onApproved, onClose }: { run: PayrollRun; onApproved: (a: PayrollRunApproval) => void; onClose: () => void }) {
  const { handleError } = useErrorHandler();
  const approve = useMutation({
    mutationFn: () => api.payroll.approvePayrollRun(run.runId),
    onSuccess: onApproved,
    onError: (error) => {
      handleError(error);
      onClose();
    },
  });
  return (
    <div className="dialog-overlay">
      <div role="dialog" aria-modal="true" aria-labelledby="approve-run-title" className="dialog">
        <h3 id="approve-run-title">Approve payroll run #{run.runId}</h3>
        <p>
          {run.employeeCount} employees · net {formatMoney(run.totalNet)}
          {run.errorCount > 0 && (
            <>
              {' '}· <strong>{run.errorCount} employee{run.errorCount === 1 ? '' : 's'} with errors will be skipped</strong>
            </>
          )}
        </p>
        <div className="actions">
          <button type="button" onClick={() => approve.mutate()} disabled={approve.isPending} data-testid="confirm-approve">Approve</button>
          <button type="button" onClick={onClose} disabled={approve.isPending}>Cancel</button>
        </div>
      </div>
    </div>
  );
}

function ApprovalResultDialog({ approval, onClose }: { approval: PayrollRunApproval; onClose: () => void }) {
  return (
    <div className="dialog-overlay">
      <div role="dialog" aria-modal="true" aria-labelledby="approval-result-title" className="dialog" data-testid="approval-result">
        <h3 id="approval-result-title">Run #{approval.run.runId} approved</h3>
        <p>
          Approved by {approval.run.approvedBy} on {formatDateTime(approval.run.approvedDate)} · net {formatMoney(approval.run.totalNet)}
        </p>
        {approval.warnings.length > 0 ? (
          <>
            <p role="status" className="banner banner-warning">
              {approval.warnings.length} employee{approval.warnings.length === 1 ? ' was' : 's were'} skipped and need{approval.warnings.length === 1 ? 's' : ''} manual follow-up.
            </p>
            <table className="grid" aria-label="Approval warnings">
              <thead><tr><th>Employee</th><th>Error</th></tr></thead>
              <tbody>
                {approval.warnings.map((w) => (
                  <tr key={w.empId}><td>{w.empNumber}</td><td>{errorLabel(w.errorCode, w.errorMessage)}</td></tr>
                ))}
              </tbody>
            </table>
          </>
        ) : (
          <p>All employees were paid.</p>
        )}
        <div className="actions">
          <button type="button" onClick={onClose}>Close</button>
        </div>
      </div>
    </div>
  );
}
