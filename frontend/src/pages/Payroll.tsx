import { useState } from 'react';
import { api, call, type Schemas } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { ActionButton, body, DataTable, ErrorBanner, SimpleForm, useLoad } from '../components/ui';

type Run = Schemas['PayrollRun'];

/** HRMS_PAYROLL: pay periods, runs (calculate only when PENDING, approve when CALCULATED — server gates). */
export function Payroll() {
  const { has } = useAuth();
  const canEdit = has('PAYROLL', 'EDIT');
  const canApprove = has('PAYROLL', 'APPROVE');
  const periods = useLoad(() => call(api.GET('/api/v1/payroll/pay-periods')), []);
  const runs = useLoad(() => call(api.GET('/api/v1/payroll/runs')), []);
  const [run, setRun] = useState<Run | null>(null);
  const payslips = useLoad(
    () => (run ? call(api.GET('/api/v1/payroll/runs/{runId}/payslips', { params: { path: { runId: run.runId! } } })) : Promise.resolve([])),
    [run?.runId],
  );
  const errors = useLoad(
    () => (run ? call(api.GET('/api/v1/payroll/runs/{runId}/errors', { params: { path: { runId: run.runId! } } })) : Promise.resolve([])),
    [run?.runId],
  );
  const runPath = (r: Run) => ({ path: { runId: r.runId! } });
  const after = (p: Promise<Run>) => p.then((r) => { setRun(r); runs.reload(); });

  return (
    <section>
      <h2>Payroll</h2>
      <h3>Pay periods</h3>
      <ErrorBanner error={periods.error} />
      <DataTable
        label="Pay periods"
        rows={periods.data}
        columns={[
          { header: 'Period', render: (p: Schemas['PayPeriod']) => p.periodName },
          { header: 'Frequency', render: (p) => p.payFrequency },
          { header: 'Start', render: (p) => p.periodStartDate },
          { header: 'End', render: (p) => p.periodEndDate },
          { header: 'Pay date', render: (p) => p.payDate },
          { header: 'Status', render: (p) => p.status },
          {
            header: '',
            render: (p) =>
              canEdit && p.status !== 'CLOSED' && (
                <ActionButton label="Close" onClick={() => call(api.POST('/api/v1/payroll/pay-periods/{periodId}/close', { params: { path: { periodId: p.periodId! } } })).then(periods.reload)} />
              ),
          },
        ]}
      />
      {canEdit && (
        <div className="row">
          <SimpleForm
            title="Generate pay periods"
            submitLabel="Generate"
            fields={[
              { name: 'year', label: 'Year', type: 'number', required: true },
              { name: 'frequency', label: 'Frequency', type: 'select', required: true, options: [{ value: 'MONTHLY', label: 'Monthly' }, { value: 'BIWEEKLY', label: 'Biweekly' }] },
            ]}
            onSubmit={(v) => call(api.POST('/api/v1/payroll/pay-periods/generate', { body: body(v, ['year']) as Schemas['GeneratePayPeriodsRequest'] })).then(periods.reload)}
          />
          {periods.data && (
            <SimpleForm
              title="New payroll run"
              submitLabel="Create run"
              fields={[{ name: 'periodId', label: 'Pay period', type: 'select', required: true, options: periods.data.map((p) => ({ value: String(p.periodId), label: p.periodName ?? '' })) }]}
              onSubmit={(v) => after(call(api.POST('/api/v1/payroll/runs', { body: body(v, ['periodId']) as Schemas['CreatePayrollRunRequest'] })))}
            />
          )}
        </div>
      )}
      <h3>Payroll runs</h3>
      <ErrorBanner error={runs.error} />
      <DataTable
        label="Payroll runs"
        rows={runs.data}
        columns={[
          { header: 'Run', render: (r: Run) => r.runId },
          { header: 'Period', render: (r) => r.periodName },
          { header: 'Status', render: (r) => r.status },
          { header: 'Employees', render: (r) => r.employeeCount ?? '' },
          { header: 'Gross', render: (r) => r.totalGross ?? '' },
          { header: 'Net', render: (r) => r.totalNet ?? '' },
          {
            header: '',
            render: (r) => (
              <>
                <button type="button" onClick={() => setRun(r)}>Details</button>
                {canEdit && r.status === 'PENDING' && <ActionButton label="Calculate" onClick={() => after(call(api.POST('/api/v1/payroll/runs/{runId}/calculate', { params: runPath(r) })))} />}
                {canApprove && r.status === 'CALCULATED' && <ActionButton label="Approve" onClick={() => after(call(api.POST('/api/v1/payroll/runs/{runId}/approve', { params: runPath(r) })))} />}
              </>
            ),
          },
        ]}
      />
      {run && (
        <div>
          <h3>Run {run.runId} — {run.status}</h3>
          {canApprove && run.status !== 'REVERSED' && (
            <SimpleForm
              title="Reverse run"
              submitLabel="Reverse"
              fields={[{ name: 'reason', label: 'Reason', required: true }]}
              onSubmit={(v) => after(call(api.POST('/api/v1/payroll/runs/{runId}/reverse', { params: runPath(run), body: { reason: v.reason ?? '' } })))}
            />
          )}
          <h4>Payslips</h4>
          <DataTable
            label="Payslips"
            rows={payslips.data}
            columns={[
              { header: 'Employee', render: (p: Schemas['Payslip']) => `${p.empNumber} ${p.employeeName}` },
              { header: 'Gross', render: (p) => p.grossPay },
              { header: 'Deductions', render: (p) => p.totalDeductions },
              { header: 'Net', render: (p) => p.netPay },
            ]}
          />
          <h4>Errors</h4>
          <DataTable label="Run errors" rows={errors.data} empty="No errors" columns={[{ header: 'Employee', render: (e: Schemas['PayrollRunError']) => e.empId }, { header: 'Message', render: (e) => e.message }]} />
        </div>
      )}
    </section>
  );
}
