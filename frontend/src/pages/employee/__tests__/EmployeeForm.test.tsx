import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { SEED_ACCOUNTS } from '../../../../e2e/seed-accounts';
import { server } from '../../../mocks/server';
import { iso, renderEmployeesAs } from './employeeTestUtils';

describe('EmployeeForm – create', () => {
  it('validates from validation-schema.json before calling the API', async () => {
    const user = userEvent.setup();
    let called = false;
    server.use(http.post('/api/employees', () => { called = true; return HttpResponse.json({}, { status: 500 }); }));
    renderEmployeesAs(SEED_ACCOUNTS.executive.email, '/employees/new');
    await screen.findByRole('heading', { name: 'New employee' });
    await user.click(screen.getByRole('button', { name: 'Create employee' }));

    expect(await screen.findByText('First name is required')).toBeInTheDocument();
    expect(screen.getByText('Last name is required')).toBeInTheDocument();
    expect(screen.getByText('Hire date is required')).toBeInTheDocument();
    expect(screen.getByText('Department is required')).toBeInTheDocument();
    expect(called).toBe(false);
  });

  it('applies the employee.hireDateLimit rule (-20501) client-side', async () => {
    const user = userEvent.setup();
    renderEmployeesAs(SEED_ACCOUNTS.executive.email, '/employees/new');
    await screen.findByRole('heading', { name: 'New employee' });
    await user.type(screen.getByLabelText('First name *'), 'Far');
    await user.type(screen.getByLabelText('Last name *'), 'Future');
    await user.type(screen.getByLabelText('Hire date *'), iso(120));
    await waitFor(() => expect(screen.getByRole('option', { name: /Engineering/ })).toBeInTheDocument());
    await user.selectOptions(screen.getByLabelText('Department *'), '3');
    await user.selectOptions(screen.getByLabelText('Job title *'), '1');
    await user.click(screen.getByRole('button', { name: 'Create employee' }));
    expect(await screen.findByText('Hire date cannot be more than 90 days in the future')).toBeInTheDocument();
  });

  it('creates an employee and navigates to the new record', async () => {
    const user = userEvent.setup();
    renderEmployeesAs(SEED_ACCOUNTS.executive.email, '/employees/new');
    await screen.findByRole('heading', { name: 'New employee' });
    await user.type(screen.getByLabelText('First name *'), 'Ada');
    await user.type(screen.getByLabelText('Last name *'), 'Lovelace');
    await user.type(screen.getByLabelText('E-mail'), 'ada.lovelace@company.com');
    await user.type(screen.getByLabelText('Hire date *'), iso(1));
    await waitFor(() => expect(screen.getByRole('option', { name: /Engineering/ })).toBeInTheDocument());
    await user.selectOptions(screen.getByLabelText('Department *'), '3');
    await user.selectOptions(screen.getByLabelText('Job title *'), '1');
    await user.type(screen.getByLabelText('Initial salary'), '50000');
    await user.click(screen.getByRole('button', { name: 'Create employee' }));

    expect(await screen.findByRole('heading', { name: /Lovelace, Ada/ })).toBeInTheDocument();
    expect(screen.getByTestId('emp-number')).toHaveTextContent(/EMP-\d{6}/);
    expect(await screen.findByText(/Employee EMP-\d+ created/)).toBeInTheDocument();
  });

  it('maps -20502 duplicate email to the email field', async () => {
    const user = userEvent.setup();
    renderEmployeesAs(SEED_ACCOUNTS.executive.email, '/employees/new');
    await screen.findByRole('heading', { name: 'New employee' });
    await user.type(screen.getByLabelText('First name *'), 'Dup');
    await user.type(screen.getByLabelText('Last name *'), 'Email');
    await user.type(screen.getByLabelText('E-mail'), SEED_ACCOUNTS.staff.email);
    await user.type(screen.getByLabelText('Hire date *'), iso(0));
    await waitFor(() => expect(screen.getByRole('option', { name: /Engineering/ })).toBeInTheDocument());
    await user.selectOptions(screen.getByLabelText('Department *'), '3');
    await user.selectOptions(screen.getByLabelText('Job title *'), '1');
    await user.click(screen.getByRole('button', { name: 'Create employee' }));

    const email = screen.getByLabelText('E-mail');
    await waitFor(() => expect(email).toHaveAttribute('aria-invalid', 'true'));
    expect(screen.getByText(/already in use/i)).toBeInTheDocument();
  });
});

describe('EmployeeForm – edit', () => {
  it('saves with If-Match and refreshes the version', async () => {
    const user = userEvent.setup();
    let ifMatch: string | null = null;
    server.events.on('request:start', ({ request }) => {
      if (request.method === 'PUT' && request.url.endsWith('/api/employees/11')) ifMatch = request.headers.get('if-match');
    });
    renderEmployeesAs(SEED_ACCOUNTS.executive.email, '/employees/11');
    await screen.findByRole('heading', { name: /MARTINEZ, DAVID/ });
    const phone = screen.getByLabelText('Work phone');
    await user.clear(phone);
    await user.type(phone, '415-555-0100');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    expect(await screen.findByText('Employee saved')).toBeInTheDocument();
    expect(ifMatch).toBe('"0"');
    await waitFor(() => expect(screen.getByLabelText('Work phone')).toHaveValue('415-555-0100'));
  });

  it('surfaces a 412 CONFLICT toast when the record was modified elsewhere', async () => {
    const user = userEvent.setup();
    server.use(
      http.put('/api/employees/11', () =>
        HttpResponse.json({ code: 'CONFLICT', message: 'Employee was modified by another user', traceId: 't-1' }, { status: 412 }),
      ),
    );
    renderEmployeesAs(SEED_ACCOUNTS.executive.email, '/employees/11');
    await screen.findByRole('heading', { name: /MARTINEZ, DAVID/ });
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('Employee was modified by another user')).toBeInTheDocument();
  });

  it('maps -20004 to the manager dropdown', async () => {
    const user = userEvent.setup();
    renderEmployeesAs(SEED_ACCOUNTS.executive.email, '/employees/11');
    await screen.findByRole('heading', { name: /MARTINEZ, DAVID/ });
    await waitFor(() => expect(screen.getByRole('option', { name: /DAVID MARTINEZ/ })).toBeInTheDocument());
    await user.selectOptions(screen.getByLabelText('Manager'), '11');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText(/Invalid or inactive manager/)).toBeInTheDocument();
  });
});
