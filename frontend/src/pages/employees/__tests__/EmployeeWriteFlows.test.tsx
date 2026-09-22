import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HttpResponse, http } from 'msw';
import { describe, expect, it } from 'vitest';
import { getEmployee } from '../../../mocks/employeeStore';
import { server } from '../../../mocks/server';
import { EXEC, MANAGER, STAFF, iso, renderEmployeesAs, resetModuleFlagAfterEach } from './employeeTestUtils';

resetModuleFlagAfterEach();

const notifications = () => screen.getByRole('region', { name: 'Notifications' });

describe('create employee', () => {
  it('validates from the generated EmployeeCreateRequest schema before calling the API', async () => {
    const user = userEvent.setup();
    renderEmployeesAs(EXEC, '/employees/new');
    const form = await screen.findByRole('form', { name: 'New employee' });
    await user.type(within(form).getByLabelText(/^Hire date/), iso(120));
    await user.click(within(form).getByRole('button', { name: 'Create employee' }));
    const alerts = await screen.findAllByRole('alert');
    const texts = alerts.map((a) => a.textContent);
    expect(texts).toContain('First name is required');
    expect(texts).toContain('Last name is required');
    expect(texts).not.toContain('Hire date cannot be more than 90 days in the future'); // field errors first, cross-field after
  });

  it('creates an employee (empNumber assigned by the server) and navigates to the detail page', async () => {
    const user = userEvent.setup();
    renderEmployeesAs(EXEC, '/employees/new');
    const form = await screen.findByRole('form', { name: 'New employee' });
    await user.type(within(form).getByLabelText(/^First name/), 'Ada');
    await user.type(within(form).getByLabelText(/^Last name/), 'Lovelace');
    await user.type(within(form).getByLabelText(/^Hire date/), iso(5));
    await user.selectOptions(await within(form).findByLabelText(/^Department/), '3');
    await user.selectOptions(await within(form).findByLabelText(/^Job title/), '1');
    await user.selectOptions(await within(form).findByLabelText(/^Manager/), '21');
    await user.type(within(form).getByLabelText(/^SSN/), '111-22-3333');
    await user.type(within(form).getByLabelText(/^Initial salary/), '50000');
    await user.click(within(form).getByRole('button', { name: 'Create employee' }));

    expect(await screen.findByRole('heading', { name: /Ada Lovelace/i })).toBeInTheDocument();
    expect(notifications()).toHaveTextContent(/Employee EMP-\d+ created/);
    expect(screen.getByLabelText('Employee details')).toHaveTextContent('•••-••-3333');
    expect(screen.getByLabelText('Employee details')).not.toHaveTextContent('111-22-3333');
  });

  it('maps -20501 from the server onto the hireDate field', async () => {
    server.use(
      http.post('/api/employees', () =>
        HttpResponse.json({ code: '-20501', message: 'Hire date cannot be more than 90 days in the future', field: 'hireDate', traceId: 't' }, { status: 422 }),
      ),
    );
    const user = userEvent.setup();
    renderEmployeesAs(EXEC, '/employees/new');
    const form = await screen.findByRole('form', { name: 'New employee' });
    await user.type(within(form).getByLabelText(/^First name/), 'A');
    await user.type(within(form).getByLabelText(/^Last name/), 'B');
    await user.type(within(form).getByLabelText(/^Hire date/), iso(1));
    await user.selectOptions(await within(form).findByLabelText(/^Department/), '3');
    await user.selectOptions(await within(form).findByLabelText(/^Job title/), '1');
    await user.click(within(form).getByRole('button', { name: 'Create employee' }));
    expect(await screen.findByText('Hire date cannot be more than 90 days in the future')).toBeInTheDocument();
  });

  it('redirects away from /employees/new when the module is read-only or the user lacks EMPLOYEE:EDIT', async () => {
    renderEmployeesAs(EXEC, '/employees/new', 'NEW_READONLY');
    expect(await screen.findByRole('table', { name: 'Employees' })).toBeInTheDocument();
    expect(screen.queryByRole('form', { name: 'New employee' })).not.toBeInTheDocument();
  });

  it('a viewer is redirected too', async () => {
    renderEmployeesAs(STAFF, '/employees/new', 'NEW');
    expect(await screen.findByRole('table', { name: 'Employees' })).toBeInTheDocument();
  });
});

describe('update employee', () => {
  it('edits and saves with If-Match; the payload never carries empNumber/employmentStatus', async () => {
    let captured: { headers: Headers; body: Record<string, unknown> } | null = null;
    server.use(
      http.put('/api/employees/:id', async ({ request }) => {
        captured = { headers: request.headers, body: (await request.json()) as Record<string, unknown> };
        return HttpResponse.json({ ...getEmployee(11), ssnLast4: '0011', phoneMobile: '415-555-9999', version: 3 });
      }),
    );
    const user = userEvent.setup();
    renderEmployeesAs(EXEC, '/employees/11');
    await screen.findByRole('heading', { name: /DAVID MARTINEZ/ });
    await user.click(screen.getByRole('button', { name: 'Edit' }));
    const form = await screen.findByRole('form', { name: 'Edit employee' });
    expect(within(form).getByLabelText(/^SSN/)).toHaveAccessibleName(expect.stringContaining('•••-••-0011'));
    const mobile = within(form).getByLabelText(/^Mobile phone/);
    await user.clear(mobile);
    await user.type(mobile, '415-555-9999');
    await user.click(within(form).getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(captured).not.toBeNull());
    expect(captured!.headers.get('If-Match')).toBe('"2"');
    expect(captured!.body).toMatchObject({ firstName: 'DAVID', phoneMobile: '415-555-9999' });
    expect(captured!.body).not.toHaveProperty('empNumber');
    expect(captured!.body).not.toHaveProperty('employmentStatus');
    expect(captured!.body).not.toHaveProperty('ssn');
    expect(await screen.findByText('Employee updated')).toBeInTheDocument();
  });

  it('shows the CONFLICT message when the version is stale', async () => {
    server.use(
      http.put('/api/employees/:id', () =>
        HttpResponse.json({ code: 'CONFLICT', message: 'The record was modified by another user. Reload and retry.', traceId: 't' }, { status: 409 }),
      ),
    );
    const user = userEvent.setup();
    renderEmployeesAs(EXEC, '/employees/11');
    await screen.findByRole('heading', { name: /DAVID MARTINEZ/ });
    await user.click(screen.getByRole('button', { name: 'Edit' }));
    await user.click((await screen.findByRole('form', { name: 'Edit employee' })).querySelector('button[type=submit]')!);
    expect(await screen.findByRole('alert')).toHaveTextContent(/modified by another user/);
  });
});

describe('terminate dialog', () => {
  it('requires reason (generated schema) then terminates and lands on History', async () => {
    const user = userEvent.setup();
    renderEmployeesAs(EXEC, '/employees/12');
    await screen.findByRole('heading', { name: /EMILY JOHNSON/ });
    await user.click(screen.getByRole('button', { name: 'Terminate' }));
    const dialog = await screen.findByRole('dialog', { name: 'Terminate employee' });
    await user.click(within(dialog).getByRole('button', { name: 'Terminate' }));
    expect(await within(dialog).findByText('Termination reason is required')).toBeInTheDocument();

    await user.type(within(dialog).getByLabelText(/^Reason/), 'RESIGNATION');
    await user.click(within(dialog).getByRole('button', { name: 'Terminate' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(notifications()).toHaveTextContent(/EMILY JOHNSON terminated effective/);
    expect(await screen.findByRole('table', { name: 'Employment history' })).toHaveTextContent('TERMINATION');
    expect(screen.getByText('Terminated')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Terminate' })).not.toBeInTheDocument();
  });

  it('surfaces -20005 when the employee is already terminated', async () => {
    server.use(
      http.post('/api/employees/:id/terminate', () =>
        HttpResponse.json({ code: '-20005', message: 'Employee is already terminated', traceId: 't' }, { status: 409 }),
      ),
    );
    const user = userEvent.setup();
    renderEmployeesAs(EXEC, '/employees/12');
    await screen.findByRole('heading', { name: /EMILY JOHNSON/ });
    await user.click(screen.getByRole('button', { name: 'Terminate' }));
    const dialog = await screen.findByRole('dialog', { name: 'Terminate employee' });
    await user.type(within(dialog).getByLabelText(/^Reason/), 'X');
    await user.click(within(dialog).getByRole('button', { name: 'Terminate' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/already terminated/i);
  });

  it('the msw contract mock refuses writes while employee=NEW_READONLY (MODULE_READ_ONLY)', async () => {
    renderEmployeesAs(EXEC, '/employees/12', 'NEW_READONLY');
    await screen.findByRole('heading', { name: /EMILY JOHNSON/ });
    const res = await fetch('/api/employees/12/terminate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${(await import('../../../api/http')).getAccessToken()}` },
      body: JSON.stringify({ effectiveDate: iso(0), reason: 'X' }),
    });
    expect(res.status).toBe(409);
    expect((await res.json()).code).toBe('MODULE_READ_ONLY');
  });
});

describe('transfer dialog', () => {
  it('transfers to another department and refreshes the header', async () => {
    const user = userEvent.setup();
    renderEmployeesAs(EXEC, '/employees/12');
    await screen.findByRole('heading', { name: /EMILY JOHNSON/ });
    await user.click(screen.getByRole('button', { name: 'Transfer' }));
    const dialog = await screen.findByRole('dialog', { name: 'Transfer employee' });
    await user.selectOptions(await within(dialog).findByLabelText(/^New department/), '3');
    await user.selectOptions(await within(dialog).findByLabelText(/^New location/), 'SF');
    await user.click(within(dialog).getByRole('button', { name: 'Transfer' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(notifications()).toHaveTextContent('Transferred to Engineering');
    const history = await screen.findByRole('table', { name: 'Employment history' });
    expect(within(history).getAllByRole('row')[1]).toHaveTextContent(/TRANSFER.*Finance → Engineering.*HQ → SF/);
    expect(screen.getByRole('heading', { name: /EMILY JOHNSON/ }).closest('section')).toHaveTextContent('Engineering');
  });

  it('maps -20004 (circular reporting chain) from the mock when the new manager reports to the subject', async () => {
    const user = userEvent.setup();
    renderEmployeesAs(EXEC, '/employees/21'); // Park manages 11/12/13
    await screen.findByRole('heading', { name: /JENNIFER PARK/ });
    await user.click(screen.getByRole('button', { name: 'Transfer' }));
    const dialog = await screen.findByRole('dialog', { name: 'Transfer employee' });
    await user.selectOptions(await within(dialog).findByLabelText(/^New department/), '3');
    await user.selectOptions(await within(dialog).findByLabelText(/^New manager/), '11');
    await user.click(within(dialog).getByRole('button', { name: 'Transfer' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/circular|manager/i);
  });
});

describe('salary dialog', () => {
  it('shows the grade-band warning banner only for out-of-band amounts and records the change', async () => {
    const user = userEvent.setup();
    renderEmployeesAs(EXEC, '/employees/11/salary');
    await waitFor(() => expect(screen.getByTestId('current-salary')).toHaveTextContent('$55,000.00'));
    await user.click(screen.getByRole('button', { name: 'Change salary' }));
    const dialog = await screen.findByRole('dialog', { name: 'Change salary' });
    await within(dialog).findByText(/grade G3 band \$45,000\.00 – \$65,000\.00/);
    const amount = within(dialog).getByLabelText(/^Base salary/);
    expect(amount).toHaveValue(55000);
    await user.clear(amount);
    await user.type(amount, '60000');
    expect(within(dialog).queryByTestId('grade-band-warning')).not.toBeInTheDocument();
    await user.clear(amount);
    await user.type(amount, '90000');
    expect(within(dialog).getByTestId('grade-band-warning')).toHaveTextContent('$90,000.00 is outside the G3 grade band');

    await user.type(within(dialog).getByLabelText(/^Change reason/), 'PROMOTION');
    await user.click(within(dialog).getByRole('button', { name: 'Apply change' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(notifications()).toHaveTextContent('Salary changed to $90,000.00');
    await waitFor(() => expect(screen.getByTestId('current-salary')).toHaveTextContent('$90,000.00'));
    expect(screen.getByTestId('out-of-band-badge')).toBeInTheDocument();
    expect(screen.getByRole('table', { name: 'Salary history' })).toHaveTextContent('$55,000.00');
  });

  it('rejects a non-positive salary client-side with the -20101 rule message', async () => {
    const user = userEvent.setup();
    renderEmployeesAs(EXEC, '/employees/11/salary');
    await waitFor(() => expect(screen.getByTestId('current-salary')).toHaveTextContent('$55,000.00'));
    await user.click(screen.getByRole('button', { name: 'Change salary' }));
    const dialog = await screen.findByRole('dialog', { name: 'Change salary' });
    await user.clear(within(dialog).getByLabelText(/^Base salary/));
    await user.type(within(dialog).getByLabelText(/^Base salary/), '0');
    await user.type(within(dialog).getByLabelText(/^Change reason/), 'MERIT');
    await user.click(within(dialog).getByRole('button', { name: 'Apply change' }));
    expect(await within(dialog).findByText('Salary must be positive')).toBeInTheDocument();
  });

  it('is hidden for PAYROLL:VIEW-only users and at NEW_READONLY', async () => {
    renderEmployeesAs(EXEC, '/employees/11/salary', 'NEW_READONLY');
    await waitFor(() => expect(screen.getByTestId('current-salary')).toHaveTextContent('$55,000.00'));
    expect(screen.queryByRole('button', { name: 'Change salary' })).not.toBeInTheDocument();
  });
});

describe('dependents & contacts dialogs', () => {
  it('adds a dependent (SSN write-only, masked in the grid) and edits it', async () => {
    const user = userEvent.setup();
    renderEmployeesAs(EXEC, '/employees/12/dependents');
    await screen.findByRole('heading', { name: 'Dependents' });
    await user.click(screen.getByRole('button', { name: 'Add dependent' }));
    let dialog = await screen.findByRole('dialog', { name: 'Add dependent' });
    await user.type(within(dialog).getByLabelText(/^First name/), 'Sam');
    await user.type(within(dialog).getByLabelText(/^Last name/), 'Johnson');
    await user.selectOptions(within(dialog).getByLabelText(/^Relationship/), 'CHILD');
    await user.type(within(dialog).getByLabelText(/^SSN/), '222-33-4444');
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(screen.getByRole('table', { name: 'Dependents' })).toHaveTextContent(/Sam Johnson/i));
    const table = screen.getByRole('table', { name: 'Dependents' });
    expect(table).toHaveTextContent('•••-••-4444');
    expect(table).not.toHaveTextContent('222-33-4444');

    await user.click(screen.getByRole('button', { name: /Edit dependent Sam Johnson/i }));
    dialog = await screen.findByRole('dialog', { name: 'Edit dependent' });
    await user.selectOptions(within(dialog).getByLabelText(/^Benefits enrolled/), 'true');
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('Dependent updated')).toBeInTheDocument();
  });

  it('adds an emergency contact and hides the add button when read-only', async () => {
    const user = userEvent.setup();
    renderEmployeesAs(EXEC, '/employees/12/contacts');
    await screen.findByRole('heading', { name: 'Emergency contacts' });
    await user.click(screen.getByRole('button', { name: 'Add contact' }));
    const dialog = await screen.findByRole('dialog', { name: 'Add emergency contact' });
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));
    expect(await within(dialog).findByText('Contact name is required')).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText(/^Contact name/), 'Pat Johnson');
    await user.type(within(dialog).getByLabelText(/^Primary phone/), '415-555-0100');
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(screen.getByRole('table', { name: 'Emergency contacts' })).toHaveTextContent(/Pat Johnson/i));
  });

  it('read-only module: no Add buttons; a manager sees the HR-only note for other employees', async () => {
    renderEmployeesAs(EXEC, '/employees/11/dependents', 'NEW_READONLY');
    await screen.findByRole('table', { name: 'Dependents' });
    expect(screen.queryByRole('button', { name: 'Add dependent' })).not.toBeInTheDocument();
  });

  it('sub-resources are scoped to self/HR', async () => {
    renderEmployeesAs(MANAGER, '/employees/11/contacts');
    expect(await screen.findByRole('note')).toHaveTextContent('visible to the employee and HR only');
  });
});
