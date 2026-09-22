import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { SEED_ACCOUNTS } from '../../../../e2e/seed-accounts';
import { server } from '../../../mocks/server';
import { iso, renderEmployeesAs } from './employeeTestUtils';

async function openDetail(id = 11) {
  renderEmployeesAs(SEED_ACCOUNTS.executive.email, `/employees/${id}`);
  await screen.findByRole('heading', { name: /,/ });
}

describe('TerminateDialog', () => {
  it('requires date and reason from the generated schema, then terminates', async () => {
    const user = userEvent.setup();
    await openDetail(13);
    await user.click(screen.getByRole('button', { name: 'Terminate' }));
    const dialog = screen.getByRole('dialog', { name: 'Terminate employee' });
    await user.click(within(dialog).getByRole('button', { name: 'Confirm termination' }));
    expect(await within(dialog).findByText('Termination date is required')).toBeInTheDocument();
    expect(within(dialog).getByText('Termination reason is required')).toBeInTheDocument();

    await user.type(within(dialog).getByLabelText('Effective date *'), iso(0));
    await user.type(within(dialog).getByLabelText('Reason *'), 'RESIGNED');
    await user.click(within(dialog).getByRole('button', { name: 'Confirm termination' }));

    expect(await screen.findByText('Employee EMP-000013 terminated')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(screen.getByTestId('emp-status')).toHaveTextContent('Terminated');
    expect(screen.queryByRole('button', { name: 'Terminate' })).not.toBeInTheDocument();
  });

  it('surfaces -20005 when the server says already terminated', async () => {
    const user = userEvent.setup();
    server.use(
      http.post('/api/employees/11/terminate', () =>
        HttpResponse.json({ code: '-20005', message: 'Employee 11 is already terminated', traceId: 't' }, { status: 422 }),
      ),
    );
    await openDetail(11);
    await user.click(screen.getByRole('button', { name: 'Terminate' }));
    const dialog = screen.getByRole('dialog');
    await user.type(within(dialog).getByLabelText('Effective date *'), iso(0));
    await user.type(within(dialog).getByLabelText('Reason *'), 'X');
    await user.click(within(dialog).getByRole('button', { name: 'Confirm termination' }));
    expect(await screen.findByText('Employee 11 is already terminated')).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });
});

describe('TransferDialog', () => {
  it('transfers to a new department and refreshes the record', async () => {
    const user = userEvent.setup();
    await openDetail(12);
    await user.click(screen.getByRole('button', { name: 'Transfer' }));
    const dialog = screen.getByRole('dialog', { name: 'Transfer employee' });
    await user.click(within(dialog).getByRole('button', { name: 'Confirm transfer' }));
    expect(await within(dialog).findByText('Effective date is required')).toBeInTheDocument();

    await user.type(within(dialog).getByLabelText('Effective date *'), iso(0));
    await waitFor(() => expect(within(dialog).getByRole('option', { name: /Engineering/ })).toBeInTheDocument());
    expect(within(dialog).getByLabelText('New department *')).toHaveValue('2');
    await user.selectOptions(within(dialog).getByLabelText('New department *'), '3');
    await user.type(within(dialog).getByLabelText('Reason code'), 'REORG');
    await user.click(within(dialog).getByRole('button', { name: 'Confirm transfer' }));

    expect(await screen.findByText('Employee EMP-000012 transferred to Engineering')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(screen.getByText('Engineering', { selector: 'dd' })).toBeInTheDocument());

    await user.click(screen.getByRole('tab', { name: 'History' }));
    const history = await screen.findByRole('table', { name: 'Employment history' });
    expect(within(history).getByText('Finance → Engineering')).toBeInTheDocument();
  });

  it('maps -20012 for non-active employees', async () => {
    const user = userEvent.setup();
    server.use(
      http.post('/api/employees/11/transfer', () =>
        HttpResponse.json({ code: '-20012', message: 'Cannot transfer non-active employee. Status: ON_LEAVE', traceId: 't' }, { status: 422 }),
      ),
    );
    await openDetail(11);
    await user.click(screen.getByRole('button', { name: 'Transfer' }));
    const dialog = screen.getByRole('dialog');
    await user.type(within(dialog).getByLabelText('Effective date *'), iso(0));
    await waitFor(() => expect(within(dialog).getByRole('option', { name: /Finance/ })).toBeInTheDocument());
    await user.selectOptions(within(dialog).getByLabelText('New department *'), '2');
    await user.click(within(dialog).getByRole('button', { name: 'Confirm transfer' }));
    expect(await screen.findByText(/Cannot transfer non-active employee/)).toBeInTheDocument();
  });
});

describe('SalaryChangeDialog', () => {
  it('shows the grade-band warning as advisory and still saves a two-decimal amount', async () => {
    const user = userEvent.setup();
    let sent: unknown;
    server.events.on('request:start', async ({ request }) => {
      if (request.method === 'POST' && request.url.endsWith('/api/employees/11/salary')) sent = await request.clone().json();
    });
    await openDetail(11);
    await user.click(screen.getByRole('button', { name: 'Change salary' }));
    const dialog = screen.getByRole('dialog', { name: 'Change salary' });
    expect(await within(dialog).findByTestId('grade-band')).toHaveTextContent('Grade G3 band: 45,000.00 – 65,000.00');
    expect(within(dialog).queryByTestId('grade-band-warning')).not.toBeInTheDocument();

    await user.type(within(dialog).getByLabelText('Base salary *'), '70000');
    expect(within(dialog).getByTestId('grade-band-warning')).toHaveTextContent('70,000.00 is outside the G3 grade band');
    expect(within(dialog).getByRole('button', { name: 'Save salary' })).toBeEnabled();

    await user.type(within(dialog).getByLabelText('Effective date *'), iso(0));
    await user.type(within(dialog).getByLabelText('Change reason *'), 'PROMOTION');
    await user.click(within(dialog).getByRole('button', { name: 'Save salary' }));

    expect(await screen.findByText('Salary set to 70,000.00 USD')).toBeInTheDocument();
    expect(sent).toMatchObject({ baseSalary: '70000.00', changeReason: 'PROMOTION' });
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await user.click(screen.getByRole('tab', { name: 'Salary' }));
    expect(await screen.findByTestId('current-salary')).toHaveTextContent('70,000.00 USD');
    expect(screen.getByText(/Current salary is outside the G3 grade band/)).toBeInTheDocument();
  });

  it('rejects non-positive salary client-side and maps server -20101 to the field', async () => {
    const user = userEvent.setup();
    await openDetail(11);
    await user.click(screen.getByRole('button', { name: 'Change salary' }));
    const dialog = screen.getByRole('dialog');
    await user.type(within(dialog).getByLabelText('Effective date *'), iso(0));
    await user.type(within(dialog).getByLabelText('Base salary *'), '0');
    await user.type(within(dialog).getByLabelText('Change reason *'), 'X');
    await user.click(within(dialog).getByRole('button', { name: 'Save salary' }));
    expect(await within(dialog).findByText('Salary must be positive')).toBeInTheDocument();

    server.use(http.post('/api/employees/11/salary', () => HttpResponse.json({ code: '-20101', message: 'Salary must be positive (server)', traceId: 't' }, { status: 422 })));
    const amount = within(dialog).getByLabelText('Base salary *');
    await user.clear(amount);
    await user.type(amount, '50000');
    await user.click(within(dialog).getByRole('button', { name: 'Save salary' }));
    expect(await within(dialog).findByText('Salary must be positive (server)')).toBeInTheDocument();
    expect(amount).toHaveAttribute('aria-invalid', 'true');
  });

  it('is hidden without PAYROLL:EDIT even at employee=NEW', async () => {
    renderEmployeesAs(SEED_ACCOUNTS.manager.email, '/employees/11');
    await screen.findByRole('heading', { name: /MARTINEZ, DAVID/ });
    expect(screen.queryByRole('button', { name: 'Change salary' })).not.toBeInTheDocument();
  });
});

describe('Dependents & Contacts tabs', () => {
  it('adds a dependent, never echoing the full SSN', async () => {
    const user = userEvent.setup();
    renderEmployeesAs(SEED_ACCOUNTS.executive.email, '/employees/12/dependents');
    await user.click(await screen.findByRole('button', { name: 'Add dependent' }));
    const form = screen.getByRole('form', { name: 'Add dependent' });
    await user.click(within(form).getByRole('button', { name: 'Add dependent' }));
    expect(await within(form).findByText('First name is required')).toBeInTheDocument();

    await user.type(within(form).getByLabelText('First name *'), 'Sam');
    await user.type(within(form).getByLabelText('Last name *'), 'Johnson');
    await user.selectOptions(within(form).getByLabelText('Relationship *'), 'CHILD');
    await user.type(within(form).getByLabelText('SSN'), '987-65-4321');
    await user.click(within(form).getByRole('button', { name: 'Add dependent' }));

    expect(await screen.findByText('Dependent added')).toBeInTheDocument();
    const table = screen.getByRole('table', { name: 'Dependents' });
    expect(within(table).getByText('Johnson, Sam')).toBeInTheDocument();
    expect(within(table).getByText('•••-••-4321')).toBeInTheDocument();
    expect(screen.queryByText('987-65-4321')).not.toBeInTheDocument();
  });

  it('edits an emergency contact and validates the phone format', async () => {
    const user = userEvent.setup();
    renderEmployeesAs(SEED_ACCOUNTS.executive.email, '/employees/11/contacts');
    await screen.findByRole('table', { name: 'Emergency contacts' });
    await user.click(screen.getByRole('button', { name: 'Edit contact CARLOS MARTINEZ' }));
    const form = screen.getByRole('form', { name: 'Edit contact' });
    const phone = within(form).getByLabelText('Primary phone *');
    await user.clear(phone);
    await user.type(phone, 'not a phone');
    await user.click(within(form).getByRole('button', { name: 'Save contact' }));
    expect(await within(form).findByText(/phone/i, { selector: '.field-error' })).toBeInTheDocument();

    await user.clear(phone);
    await user.type(phone, '415-555-0199');
    await user.click(within(form).getByRole('button', { name: 'Save contact' }));
    expect(await screen.findByText('Contact updated')).toBeInTheDocument();
    expect(within(screen.getByRole('table', { name: 'Emergency contacts' })).getByText('415-555-0199')).toBeInTheDocument();
  });

  it('hides add/edit controls at employee=NEW_READONLY', async () => {
    renderEmployeesAs(SEED_ACCOUNTS.executive.email, '/employees/11/dependents', 'NEW_READONLY');
    await screen.findByRole('table', { name: 'Dependents' });
    expect(screen.queryByRole('button', { name: 'Add dependent' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Edit dependent/ })).not.toBeInTheDocument();
  });
});
