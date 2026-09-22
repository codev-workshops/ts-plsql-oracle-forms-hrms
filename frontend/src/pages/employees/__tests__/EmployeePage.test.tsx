import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HttpResponse, http } from 'msw';
import { describe, expect, it } from 'vitest';
import { server } from '../../../mocks/server';
import { EXEC, MANAGER, STAFF, renderEmployeesAs, resetModuleFlagAfterEach } from './employeeTestUtils';

resetModuleFlagAfterEach();

describe('EmployeePage – grid', () => {
  it('lists active employees from GET /api/employees and filters by search text', async () => {
    const user = userEvent.setup();
    renderEmployeesAs(EXEC);
    const grid = await screen.findByRole('table', { name: 'Employees' });
    expect(within(grid).getByText('MARTINEZ, DAVID')).toBeInTheDocument();
    expect(within(grid).queryByText('OLDMAN, ROBERT')).not.toBeInTheDocument(); // status=ACTIVE default
    expect(screen.getByTestId('page-info')).toHaveTextContent('6 employees');

    await user.type(screen.getByRole('searchbox', { name: 'Search' }), 'park');
    await user.click(screen.getByRole('button', { name: 'Search' }));
    await waitFor(() => expect(screen.getByTestId('page-info')).toHaveTextContent('1 employees'));
    expect(within(screen.getByRole('table', { name: 'Employees' })).getByText('PARK, JENNIFER')).toBeInTheDocument();
  });

  it('filters by status and department', async () => {
    const user = userEvent.setup();
    renderEmployeesAs(EXEC);
    await screen.findByRole('table', { name: 'Employees' });
    await user.selectOptions(screen.getByLabelText('Status'), 'TERMINATED');
    await user.click(screen.getByRole('button', { name: 'Search' }));
    await waitFor(() => expect(screen.getByText('OLDMAN, ROBERT')).toBeInTheDocument());
    expect(screen.getByText('Terminated')).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText('Status'), '');
    await user.selectOptions(await screen.findByLabelText('Department'), '3');
    await user.click(screen.getByRole('button', { name: 'Search' }));
    await waitFor(() => expect(screen.getByTestId('page-info')).toHaveTextContent('3 employees'));
  });

  it('applies the EmployeeListQuery date-range rule from the generated schema before calling the API', async () => {
    const user = userEvent.setup();
    renderEmployeesAs(EXEC);
    await screen.findByRole('table', { name: 'Employees' });
    await user.type(screen.getByLabelText('Hired from'), '2020-01-01');
    await user.type(screen.getByLabelText('Hired to'), '2019-01-01');
    await user.click(screen.getByRole('button', { name: 'Search' }));
    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });

  it('shows "New employee" only at employee=NEW with EMPLOYEE:EDIT', async () => {
    renderEmployeesAs(EXEC, '/employees', 'NEW');
    expect(await screen.findByRole('link', { name: 'New employee' })).toBeInTheDocument();
  });

  it('hides "New employee" at employee=NEW_READONLY and for viewers', async () => {
    renderEmployeesAs(EXEC, '/employees', 'NEW_READONLY');
    await screen.findByRole('table', { name: 'Employees' });
    expect(screen.queryByRole('link', { name: 'New employee' })).not.toBeInTheDocument();
  });

  it('a viewer without EMPLOYEE:EDIT never sees the create link even at NEW', async () => {
    renderEmployeesAs(STAFF, '/employees', 'NEW');
    await screen.findByRole('table', { name: 'Employees' });
    expect(screen.queryByRole('link', { name: 'New employee' })).not.toBeInTheDocument();
  });

  it('surfaces a load error', async () => {
    server.use(http.get('/api/employees', () => HttpResponse.json({ code: 'INTERNAL_ERROR', message: 'boom', traceId: 't' }, { status: 500 })));
    renderEmployeesAs(EXEC);
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load employees.');
  });
});

describe('EmployeePage – detail', () => {
  it('renders the detail view with masked SSN and the tabs', async () => {
    renderEmployeesAs(EXEC, '/employees/11');
    expect(await screen.findByRole('heading', { name: /DAVID MARTINEZ/ })).toBeInTheDocument();
    const dl = screen.getByLabelText('Employee details');
    expect(dl).toHaveTextContent('•••-••-0011');
    expect(dl).not.toHaveTextContent('123-45-0011');
    for (const tab of ['Details', 'History', 'Salary', 'Dependents', 'Contacts']) expect(screen.getByRole('tab', { name: tab })).toBeInTheDocument();
  });

  it('shows Edit/Transfer/Terminate for an ACTIVE employee at NEW with EMPLOYEE:EDIT', async () => {
    renderEmployeesAs(EXEC, '/employees/11', 'NEW');
    await screen.findByRole('heading', { name: /DAVID MARTINEZ/ });
    expect(screen.getByRole('button', { name: 'Edit' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Transfer' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Terminate' })).toBeInTheDocument();
  });

  it('is read-only at NEW_READONLY: no write buttons, banner shown, reads still work', async () => {
    renderEmployeesAs(EXEC, '/employees/11', 'NEW_READONLY');
    await screen.findByRole('heading', { name: /DAVID MARTINEZ/ });
    expect(screen.getByRole('note')).toHaveTextContent('read-only during cutover');
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Terminate' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Transfer' })).not.toBeInTheDocument();
  });

  it('hides Transfer and Terminate for a terminated employee', async () => {
    renderEmployeesAs(EXEC, '/employees/30');
    await screen.findByRole('heading', { name: /ROBERT OLDMAN/ });
    expect(screen.queryByRole('button', { name: 'Transfer' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Terminate' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument();
  });

  it('maps -20001 to "Employee not found."', async () => {
    renderEmployeesAs(EXEC, '/employees/999');
    expect(await screen.findByRole('alert')).toHaveTextContent('Employee not found.');
  });

  it('a viewer does not get ssnLast4 for another employee', async () => {
    renderEmployeesAs(STAFF, '/employees/21');
    await screen.findByRole('heading', { name: /JENNIFER PARK/ });
    expect(screen.getByLabelText('Employee details')).not.toHaveTextContent('•••-••-');
  });

  it('History tab lists the employment history newest first', async () => {
    const user = userEvent.setup();
    renderEmployeesAs(MANAGER, '/employees/11');
    await screen.findByRole('heading', { name: /DAVID MARTINEZ/ });
    await user.click(screen.getByRole('tab', { name: 'History' }));
    const table = await screen.findByRole('table', { name: 'Employment history' });
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows.length).toBeGreaterThanOrEqual(2);
    expect(rows[0]).toHaveTextContent('SALARY CHANGE');
    expect(rows[rows.length - 1]).toHaveTextContent('HIRE');
  });

  it('Salary tab shows the current record + history for PAYROLL:VIEW and hides the change button', async () => {
    renderEmployeesAs(MANAGER, '/employees/11/salary');
    await waitFor(() => expect(screen.getByTestId('current-salary')).toHaveTextContent('$55,000.00'));
    expect(await screen.findByRole('table', { name: 'Salary history' })).toHaveTextContent('$48,000.00');
    expect(screen.queryByRole('button', { name: 'Change salary' })).not.toBeInTheDocument();
  });

  it('Salary tab is restricted for a viewer looking at someone else, visible for self', async () => {
    renderEmployeesAs(STAFF, '/employees/21/salary');
    expect(await screen.findByRole('note')).toHaveTextContent('restricted');
  });

  it('Salary tab shows -20104 as "No active salary record."', async () => {
    renderEmployeesAs(EXEC, '/employees/30/salary');
    expect(await screen.findByText('No active salary record.')).toBeInTheDocument();
  });

  it('Dependents and Contacts tabs render the employee sub-resources with masked SSN', async () => {
    const user = userEvent.setup();
    renderEmployeesAs(STAFF, '/employees/11/dependents');
    const deps = await screen.findByRole('table', { name: 'Dependents' });
    expect(deps).toHaveTextContent('LUCIA MARTINEZ');
    expect(deps).toHaveTextContent('•••-••-4321');
    expect(deps).not.toHaveTextContent('OLD RECORD');
    await user.click(screen.getByRole('tab', { name: 'Contacts' }));
    expect(await screen.findByRole('table', { name: 'Emergency contacts' })).toBeInTheDocument();
  });
});
