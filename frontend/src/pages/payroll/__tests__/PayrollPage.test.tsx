import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HttpResponse, http } from 'msw';
import { SEED_ACCOUNTS } from '../../../../e2e/seed-accounts';
import { server } from '../../../mocks/server';
import { renderPayrollAs } from './payrollTestUtils';

const manager = SEED_ACCOUNTS.manager.email; // PAYROLL:VIEW only
const executive = SEED_ACCOUNTS.executive.email; // PAYROLL:VIEW + PAYROLL:APPROVE

describe('PayrollPage – gating', () => {
  it('redirects home unless the proxy reports payroll=NEW', async () => {
    renderPayrollAs(executive, '/payroll', 'LEGACY');
    expect(await screen.findByRole('heading', { name: 'Home' })).toBeInTheDocument();
    expect(screen.queryByRole('tablist', { name: 'Payroll' })).not.toBeInTheDocument();
  });

  it('is not mounted at NEW_READONLY either (payroll goes live only after the shadow gate)', async () => {
    renderPayrollAs(executive, '/payroll', 'NEW_READONLY');
    expect(await screen.findByRole('heading', { name: 'Home' })).toBeInTheDocument();
  });

  it('renders the three tabs at payroll=NEW; Runs / Details are disabled until a period / run is chosen', async () => {
    renderPayrollAs(manager);
    await screen.findByRole('table', { name: 'Pay periods' });
    const tabs = screen.getAllByRole('tab');
    expect(tabs.map((t) => t.textContent)).toEqual(['Pay Periods', 'Payroll Runs', 'Pay Details']);
    expect(tabs[0]).toHaveAttribute('aria-selected', 'true');
    expect(tabs[1]).toBeDisabled();
    expect(tabs[2]).toBeDisabled();
  });
});

describe('PayPeriodsTab', () => {
  it('lists pay periods newest first with status and latest-run badges', async () => {
    renderPayrollAs(manager);
    const table = await screen.findByRole('table', { name: 'Pay periods' });
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(4);
    expect(rows[0]).toHaveTextContent('2024-04 Monthly');
    expect(rows[0]).toHaveTextContent('05/06/2024');
    expect(within(rows[3]).getByText('Closed', { selector: '.badge' })).toBeInTheDocument();
    expect(within(rows[3]).getByText('Approved', { selector: '.badge' })).toBeInTheDocument();
    expect(within(rows[2]).getByText('Calculated', { selector: '.badge' })).toBeInTheDocument();
  });

  it('filters by status through the generated PayPeriodListQuery enum', async () => {
    const user = userEvent.setup();
    renderPayrollAs(manager);
    await screen.findByRole('table', { name: 'Pay periods' });
    const select = screen.getByLabelText('Status');
    expect(within(select).getAllByRole('option').map((o) => o.textContent)).toEqual(['All', 'Open', 'Processing', 'Closed', 'Reversed']);
    await user.selectOptions(select, 'OPEN');
    await waitFor(() => expect(screen.getAllByTestId(/period-row-/)).toHaveLength(2));
  });

  it('hides "Close period" without PAYROLL:APPROVE and closes a period with it', async () => {
    const user = userEvent.setup();
    const { unmount } = renderPayrollAs(manager);
    await screen.findByRole('table', { name: 'Pay periods' });
    expect(screen.queryByRole('button', { name: 'Close period' })).not.toBeInTheDocument();
    unmount();

    renderPayrollAs(executive);
    await screen.findByRole('table', { name: 'Pay periods' });
    const row = screen.getByTestId('period-row-202403');
    await user.click(within(row).getByRole('button', { name: 'Close period' }));
    const dialog = screen.getByRole('dialog', { name: 'Close pay period' });
    await user.click(within(dialog).getByRole('button', { name: 'Close period' }));
    await waitFor(() => expect(within(screen.getByTestId('period-row-202403')).getByText('Closed', { selector: '.badge' })).toBeInTheDocument());
    expect(screen.getByRole('status', { name: '' })).toHaveTextContent('Pay period 2024-03 Monthly closed.');
  });

  it('surfaces -20102 from the server as a toast (already closed)', async () => {
    const user = userEvent.setup();
    server.use(
      http.post('/api/payroll/periods/:periodId/close', () =>
        HttpResponse.json({ code: '-20102', message: 'Pay period is already closed', traceId: 't1' }, { status: 422 }),
      ),
    );
    renderPayrollAs(executive);
    await screen.findByRole('table', { name: 'Pay periods' });
    await user.click(within(screen.getByTestId('period-row-202403')).getByRole('button', { name: 'Close period' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Close period' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Pay period is already closed');
  });
});

describe('PayrollRunsTab', () => {
  it('shows runs for the period with the error-count badge; no write buttons for PAYROLL:VIEW only', async () => {
    const user = userEvent.setup();
    renderPayrollAs(manager);
    await screen.findByRole('table', { name: 'Pay periods' });
    await user.click(screen.getByTestId('period-runs-202402'));
    await screen.findByRole('table', { name: 'Payroll runs' });
    expect(screen.getByTestId('runs-period-name')).toHaveTextContent('2024-02 Monthly');
    expect(screen.getByRole('tab', { name: 'Payroll Runs' })).toHaveAttribute('aria-selected', 'true');
    const row = screen.getByTestId('run-row-1002');
    expect(within(row).getByText('Calculated', { selector: '.badge' })).toBeInTheDocument();
    expect(screen.getByTestId('run-errors-1002')).toHaveTextContent('1 error');
    expect(within(row).getByRole('button', { name: 'Details' })).toBeInTheDocument();
    expect(within(row).getByRole('button', { name: 'Register CSV' })).toBeInTheDocument();
    expect(screen.queryByTestId('create-run')).not.toBeInTheDocument();
    expect(within(row).queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();
    expect(within(row).queryByRole('button', { name: /calculate/i })).not.toBeInTheDocument();
    expect(within(row).queryByRole('button', { name: 'Reverse' })).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/include bank/)).not.toBeInTheDocument();
  });

  it('Create run validates runType from validation-schema.json and posts the new PENDING run', async () => {
    const user = userEvent.setup();
    renderPayrollAs(executive, '/payroll/periods/202403/runs');
    await screen.findByRole('table', { name: 'Payroll runs' });
    expect(screen.getByText('No payroll runs for this period.')).toBeInTheDocument();
    await user.click(screen.getByTestId('create-run'));
    const dialog = screen.getByRole('dialog', { name: 'Create payroll run' });
    const select = within(dialog).getByLabelText(/Run type/);
    expect(within(select).getAllByRole('option').map((o) => o.textContent)).toEqual(['— Select —', 'Regular', 'Supplemental', 'Bonus', 'Final']);
    await user.selectOptions(select, '');
    await user.click(within(dialog).getByRole('button', { name: 'Create run' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('runType is required');

    await user.selectOptions(select, 'BONUS');
    await user.click(within(dialog).getByRole('button', { name: 'Create run' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    const row = await screen.findByTestId('run-row-1003');
    expect(row).toHaveTextContent('Bonus');
    expect(within(row).getByText('Pending', { selector: '.badge' })).toBeInTheDocument();
    expect(within(row).getByRole('button', { name: 'Calculate' })).toBeInTheDocument();
  });

  it('Create run is refused for a closed period with -20102 (toast, dialog stays open)', async () => {
    const user = userEvent.setup();
    renderPayrollAs(executive, '/payroll/periods/202403/runs');
    await screen.findByRole('table', { name: 'Payroll runs' });
    server.use(http.post('/api/payroll/periods/:periodId/runs', () => HttpResponse.json({ code: '-20102', message: 'Pay period is already closed', traceId: 't2' }, { status: 422 })));
    await user.click(screen.getByTestId('create-run'));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Create run' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Pay period is already closed');
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('Calculate polls /status with a progress indicator until CALCULATED, then shows the error badge', async () => {
    const user = userEvent.setup();
    renderPayrollAs(executive, '/payroll/periods/202403/runs');
    await screen.findByRole('table', { name: 'Payroll runs' });
    await user.click(screen.getByTestId('create-run'));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Create run' }));
    const row = await screen.findByTestId('run-row-1003');
    await user.click(within(row).getByRole('button', { name: 'Calculate' }));

    const progress = await screen.findByTestId('run-progress-1003');
    expect(progress).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId('run-progress-1003')).toHaveTextContent('2 / 5'));
    await waitFor(() => expect(within(screen.getByTestId('run-row-1003')).getByText('Calculated', { selector: '.badge' })).toBeInTheDocument(), { timeout: 6000 });
    expect(screen.queryByTestId('run-progress-1003')).not.toBeInTheDocument();
    expect(screen.getByTestId('run-errors-1003')).toHaveTextContent('1 error');
    const done = screen.getByTestId('run-row-1003');
    expect(within(done).getByRole('button', { name: 'Approve' })).toBeInTheDocument();
    expect(within(done).getByRole('button', { name: 'Recalculate' })).toBeInTheDocument();
  }, 10000);

  it('maps RUN_ALREADY_CALCULATING (409) to a toast', async () => {
    const user = userEvent.setup();
    server.use(http.post('/api/payroll/runs/:runId/calculate', () => HttpResponse.json({ code: 'RUN_ALREADY_CALCULATING', message: 'Payroll run is already being calculated', traceId: 't3' }, { status: 409 })));
    renderPayrollAs(executive, '/payroll/periods/202402/runs');
    await screen.findByRole('table', { name: 'Payroll runs' });
    await user.click(screen.getByTestId('run-calculate-1002'));
    expect(await screen.findByRole('alert')).toHaveTextContent('already being calculated');
  });

  it('Approve confirms, then lists the skipped employees as warnings', async () => {
    const user = userEvent.setup();
    renderPayrollAs(executive, '/payroll/periods/202402/runs');
    await screen.findByRole('table', { name: 'Payroll runs' });
    await user.click(screen.getByTestId('run-approve-1002'));
    const confirm = screen.getByRole('dialog', { name: 'Approve payroll run #1002' });
    expect(confirm).toHaveTextContent('1 employee with errors will be skipped');
    await user.click(screen.getByTestId('confirm-approve'));
    const result = await screen.findByTestId('approval-result');
    expect(result).toHaveTextContent('Run #1002 approved');
    expect(within(result).getByRole('table', { name: 'Approval warnings' })).toHaveTextContent('EMP-000022');
    expect(result).toHaveTextContent('-20104');
    await user.click(within(result).getByRole('button', { name: 'Close' }));
    await waitFor(() => expect(within(screen.getByTestId('run-row-1002')).getByText('Approved', { selector: '.badge' })).toBeInTheDocument());
    expect(screen.queryByTestId('run-approve-1002')).not.toBeInTheDocument();
    expect(screen.getByTestId('run-reverse-1002')).toBeInTheDocument();
  });

  it('maps -20103 from approve to a toast', async () => {
    const user = userEvent.setup();
    server.use(http.post('/api/payroll/runs/:runId/approve', () => HttpResponse.json({ code: '-20103', message: 'Cannot approve run in current status: PENDING', traceId: 't4' }, { status: 422 })));
    renderPayrollAs(executive, '/payroll/periods/202402/runs');
    await screen.findByRole('table', { name: 'Payroll runs' });
    await user.click(screen.getByTestId('run-approve-1002'));
    await user.click(screen.getByTestId('confirm-approve'));
    expect(await screen.findByRole('alert')).toHaveTextContent('Cannot approve run in current status');
  });

  it('Reverse requires a reason (generated rule) and reopens the period', async () => {
    const user = userEvent.setup();
    renderPayrollAs(executive, '/payroll/periods/202402/runs');
    await screen.findByRole('table', { name: 'Payroll runs' });
    await user.click(screen.getByTestId('run-reverse-1002'));
    const dialog = screen.getByRole('dialog', { name: 'Reverse payroll run #1002' });
    await user.click(within(dialog).getByRole('button', { name: 'Reverse run' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('A reversal reason is required');
    await user.type(within(dialog).getByLabelText(/Reason/), 'Wrong tax year');
    await user.click(within(dialog).getByRole('button', { name: 'Reverse run' }));
    await waitFor(() => expect(within(screen.getByTestId('run-row-1002')).getByText('Reversed', { selector: '.badge' })).toBeInTheDocument());
    expect(screen.getByText('Open', { selector: '.badge' })).toBeInTheDocument();
  });

  it('downloads the register CSV (bank columns only with PAYROLL:APPROVE)', async () => {
    const user = userEvent.setup();
    let requested: URL | null = null;
    server.events.on('request:start', ({ request }) => {
      if (request.url.includes('register.csv')) requested = new URL(request.url);
    });
    renderPayrollAs(executive, '/payroll/periods/202401/runs');
    await screen.findByRole('table', { name: 'Payroll runs' });
    await user.click(screen.getByLabelText(/include bank/));
    await user.click(screen.getByTestId('run-register-1001'));
    expect(await screen.findByText(/Downloaded PAY_REGISTER_1001_2024_01_Monthly\.csv/)).toBeInTheDocument();
    expect(requested!.searchParams.get('includeBank')).toBe('true');
  });
});

describe('PayDetailsTab', () => {
  it('lists detail rows, highlights ERROR rows with the legacy code and links to payslips', async () => {
    renderPayrollAs(manager, '/payroll/runs/1002/details');
    const table = await screen.findByRole('table', { name: 'Pay details' });
    expect(screen.getByRole('tab', { name: 'Pay Details' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByTestId('detail-error-banner')).toHaveTextContent('1 employee on this page could not be calculated');
    const errorRow = within(table).getByText('-20104: No active salary record for employee 22').closest('tr')!;
    expect(within(errorRow).getByText('Error', { selector: '.badge' })).toBeInTheDocument();
    expect(within(errorRow).queryByRole('link')).not.toBeInTheDocument();
    expect(screen.getByTestId('payslip-link-21')).toHaveAttribute('href', '/payroll/runs/1002/payslips/21');
    // stored sign: earnings positive, taxes negative
    const park = within(table).getAllByRole('row').filter((r) => r.textContent?.includes('EMP-000021'));
    expect(park[0]).toHaveTextContent('BASE_PAY');
    expect(park[0]).toHaveTextContent('8,000.00');
    expect(park[1]).toHaveTextContent('FED_TAX');
    expect(park[1]).toHaveTextContent('-960.00');
  });

  it('validates the empId filter with the generated PayrollDetailListQuery rule and filters by status', async () => {
    const user = userEvent.setup();
    renderPayrollAs(manager, '/payroll/runs/1002/details');
    await screen.findByRole('table', { name: 'Pay details' });
    await user.type(screen.getByLabelText('Employee id'), '0');
    await user.click(screen.getByRole('button', { name: 'Apply' }));
    expect(await screen.findByRole('alert')).toBeInTheDocument();

    await user.clear(screen.getByLabelText('Employee id'));
    await user.selectOptions(screen.getByLabelText('Status'), 'ERROR');
    await user.click(screen.getByRole('button', { name: 'Apply' }));
    await waitFor(() => expect(screen.getAllByTestId(/detail-row-/)).toHaveLength(1));
  });
});

describe('PayslipView', () => {
  it('renders the payslip with period/YTD columns and lines', async () => {
    renderPayrollAs(manager, '/payroll/runs/1002/payslips/21');
    const slip = await screen.findByTestId('payslip');
    expect(slip).toHaveTextContent('Payslip · JENNIFER PARK (EMP-000021)');
    expect(screen.getByTestId('payslip-gross')).toHaveTextContent('8,000.00');
    // 8000 - 960 (fed) - 744 (CA 9.3%) - 496 (FICA) - 116 (medicare)
    expect(screen.getByTestId('payslip-net')).toHaveTextContent('5,684.00');
    const summary = screen.getByRole('table', { name: 'Payslip summary' });
    // YTD = approved January run only (February is still CALCULATED)
    expect(within(summary).getAllByRole('row')[1]).toHaveTextContent('8,000.00');
    expect(within(screen.getByRole('table', { name: 'Payslip lines' })).getAllByRole('row')).toHaveLength(6);
  });

  it('shows the legacy -20104 code inline when the employee has only an ERROR row', async () => {
    renderPayrollAs(manager, '/payroll/runs/1002/payslips/22');
    expect(await screen.findByTestId('payslip-error')).toHaveTextContent('-20104: No active salary record for employee 22');
  });

  it('shows PAYSLIP_NOT_FOUND for an employee outside the run', async () => {
    renderPayrollAs(manager, '/payroll/runs/1002/payslips/999');
    expect(await screen.findByTestId('payslip-error')).toHaveTextContent('PAYSLIP_NOT_FOUND');
  });
});
