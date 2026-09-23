import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { SEED_ACCOUNTS } from '../../../../e2e/seed-accounts';
import { server } from '../../../mocks/server';
import { renderEmployeesAs } from './employeeTestUtils';

describe('EmployeePage – grid', () => {
  it('lists active employees and filters by search text', async () => {
    const user = userEvent.setup();
    renderEmployeesAs(SEED_ACCOUNTS.staff.email);
    const table = await screen.findByRole('table', { name: 'Employees' });
    expect(within(table).getByRole('link', { name: 'EMP-000011' })).toBeInTheDocument();
    expect(within(table).queryByText('OLDMAN, ROBERT')).not.toBeInTheDocument();

    await user.type(screen.getByLabelText('Name or number'), 'park');
    await user.click(screen.getByRole('button', { name: 'Search' }));
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(2));
    expect(screen.getByRole('link', { name: 'PARK, JENNIFER' })).toBeInTheDocument();
  });

  it('includes inactive employees when asked', async () => {
    const user = userEvent.setup();
    renderEmployeesAs(SEED_ACCOUNTS.staff.email);
    await screen.findByRole('table', { name: 'Employees' });
    await user.click(screen.getByLabelText('Include inactive'));
    await user.click(screen.getByRole('button', { name: 'Search' }));
    expect(await screen.findByText('OLDMAN, ROBERT')).toBeInTheDocument();
    expect(screen.getByText('TERMINATED', { selector: '.badge' })).toBeInTheDocument();
  });

  it('shows "New employee" only for EMPLOYEE:EDIT and only when employee=NEW', async () => {
    renderEmployeesAs(SEED_ACCOUNTS.executive.email);
    await screen.findByRole('table', { name: 'Employees' });
    expect(screen.getByRole('button', { name: 'New employee' })).toBeInTheDocument();
    expect(screen.queryByTestId('read-only-banner')).not.toBeInTheDocument();
  });

  it('is read-only at employee=NEW_READONLY even for executives', async () => {
    renderEmployeesAs(SEED_ACCOUNTS.executive.email, '/employees', 'NEW_READONLY');
    await screen.findByRole('table', { name: 'Employees' });
    expect(screen.getByTestId('read-only-banner')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'New employee' })).not.toBeInTheDocument();
  });

  it('staff never see the create button', async () => {
    renderEmployeesAs(SEED_ACCOUNTS.staff.email);
    await screen.findByRole('table', { name: 'Employees' });
    expect(screen.queryByRole('button', { name: 'New employee' })).not.toBeInTheDocument();
  });

  it('redirects /employees/new to the grid when writes are disabled', async () => {
    renderEmployeesAs(SEED_ACCOUNTS.executive.email, '/employees/new', 'NEW_READONLY');
    expect(await screen.findByRole('table', { name: 'Employees' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'New employee' })).not.toBeInTheDocument();
  });
});

describe('EmployeePage – detail record', () => {
  it('renders the five tabs and routes between them', async () => {
    const user = userEvent.setup();
    renderEmployeesAs(SEED_ACCOUNTS.executive.email, '/employees/11');
    await screen.findByRole('heading', { name: /MARTINEZ, DAVID/ });
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual(['Details', 'History', 'Salary', 'Dependents', 'Contacts']);
    expect(screen.getByRole('tab', { name: 'Details' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByTestId('emp-number')).toHaveTextContent('EMP-000011');
    expect(screen.getByText('•••-••-6789')).toBeInTheDocument();
    expect(screen.queryByText('123-45-6789')).not.toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: 'History' }));
    expect(await screen.findByRole('table', { name: 'Employment history' })).toBeInTheDocument();
    expect(screen.getByText('Finance → Engineering')).toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: 'Salary' }));
    expect(await screen.findByTestId('current-salary')).toHaveTextContent('58,000.00 USD');
    expect(await screen.findByRole('table', { name: 'Salary history' })).toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: 'Dependents' }));
    expect(await screen.findByRole('table', { name: 'Dependents' })).toBeInTheDocument();
    expect(screen.getByText('MARTINEZ, LUCIA')).toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: 'Contacts' }));
    expect(await screen.findByRole('table', { name: 'Emergency contacts' })).toBeInTheDocument();
    expect(screen.getByText('CARLOS MARTINEZ')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Contacts' })).toHaveAttribute('aria-selected', 'true');
  });

  it('shows the action buttons at employee=NEW and hides them at NEW_READONLY', async () => {
    const { unmount } = renderEmployeesAs(SEED_ACCOUNTS.executive.email, '/employees/11');
    await screen.findByRole('heading', { name: /MARTINEZ, DAVID/ });
    expect(screen.getByRole('button', { name: 'Terminate' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Transfer' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Change salary' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save' })).toBeInTheDocument();
    unmount();

    renderEmployeesAs(SEED_ACCOUNTS.executive.email, '/employees/11', 'NEW_READONLY');
    await screen.findByRole('heading', { name: /MARTINEZ, DAVID/ });
    expect(screen.queryByRole('button', { name: 'Terminate' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Transfer' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Change salary' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument();
    expect(screen.getByLabelText('First name *')).toBeDisabled();
  });

  it('hides lifecycle actions for a terminated employee', async () => {
    renderEmployeesAs(SEED_ACCOUNTS.executive.email, '/employees/9');
    await screen.findByRole('heading', { name: /OLDMAN, ROBERT/ });
    expect(screen.getByTestId('emp-status')).toHaveTextContent('Terminated');
    expect(screen.queryByRole('button', { name: 'Terminate' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument();
  });

  it('reports -20001 when the employee does not exist', async () => {
    renderEmployeesAs(SEED_ACCOUNTS.executive.email, '/employees/4242');
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load employee 4242');
  });

  it('masks ssnLast4 for readers who are not EMPLOYEE:EDIT / self', async () => {
    renderEmployeesAs(SEED_ACCOUNTS.staff.email, '/employees/21');
    await screen.findByRole('heading', { name: /PARK, JENNIFER/ });
    expect(screen.queryByText(/•••-••-\d{4}/)).not.toBeInTheDocument();
  });

  it('does not fetch scoped tabs for another employee without authority', async () => {
    const requests: string[] = [];
    const onRequest = ({ request }: { request: Request }) => {
      if (/\/api\/employees\/21\/(salary|dependents|contacts)/.test(request.url)) requests.push(request.url);
    };
    server.events.on('request:start', onRequest);
    try {
      renderEmployeesAs(SEED_ACCOUNTS.staff.email, '/employees/21/salary');
      await screen.findByRole('heading', { name: /PARK, JENNIFER/ });
      expect(screen.getByRole('alert')).toHaveTextContent('not permitted');
      expect(screen.queryByRole('tab', { name: 'Salary' })).not.toBeInTheDocument();
      expect(screen.queryByRole('tab', { name: 'Dependents' })).not.toBeInTheDocument();
      expect(screen.queryByRole('tab', { name: 'Contacts' })).not.toBeInTheDocument();
      expect(requests).toHaveLength(0);
    } finally {
      server.events.removeListener('request:start', onRequest);
    }
  });

  it('allows self-service related writes only under NEW, not employee edits', async () => {
    const user = userEvent.setup();
    const { unmount } = renderEmployeesAs(SEED_ACCOUNTS.staff.email, '/employees/11/dependents');
    await screen.findByRole('table', { name: 'Dependents' });
    expect(screen.getByRole('button', { name: 'Add dependent' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Edit dependent LUCIA MARTINEZ' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Add dependent' }));
    expect(screen.getByLabelText('SSN')).toHaveAttribute('type', 'password');
    expect(screen.queryByRole('button', { name: 'Transfer' })).not.toBeInTheDocument();
    unmount();

    renderEmployeesAs(SEED_ACCOUNTS.staff.email, '/employees/11/contacts', 'NEW_READONLY');
    await screen.findByRole('table', { name: 'Emergency contacts' });
    expect(screen.queryByRole('button', { name: 'Add contact' })).not.toBeInTheDocument();
  });

  it('hides related writes on terminated employees', async () => {
    renderEmployeesAs(SEED_ACCOUNTS.executive.email, '/employees/9/dependents');
    await screen.findByRole('heading', { name: /OLDMAN, ROBERT/ });
    expect(screen.queryByRole('button', { name: 'Add dependent' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Edit dependent/ })).not.toBeInTheDocument();
  });
});
