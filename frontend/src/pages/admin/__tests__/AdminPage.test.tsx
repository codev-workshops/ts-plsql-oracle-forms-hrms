import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HttpResponse, http } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { SEED_ACCOUNTS } from '../../../../e2e/seed-accounts';
import { setAccessToken } from '../../../api/http';
import type { ModuleFlagValue } from '../../../app/modules';
import { MOCK_USERS, seedAccessToken } from '../../../mocks/handlers';
import { server } from '../../../mocks/server';
import { renderWithProviders } from '../../../test/render';
import { AdminPage } from '../AdminPage';

const executive = SEED_ACCOUNTS.executive.email; // ADMIN:VIEW + ADMIN:EDIT + LEAVE:ADMIN
const manager = SEED_ACCOUNTS.manager.email; // ADMIN:VIEW only
const staff = SEED_ACCOUNTS.staff.email;

const routes = (
  <Routes>
    <Route path="/" element={<h1>Home</h1>} />
    <Route path="/admin/*" element={<AdminPage />} />
  </Routes>
);

function renderAdminAs(email: string, path = '/admin', flag: ModuleFlagValue = 'NEW') {
  setAccessToken(seedAccessToken(email));
  return renderWithProviders(routes, { initialUser: MOCK_USERS[email], initialEntries: [path], moduleFlags: { reporting: flag } });
}

/** Required fields render as "Label *"; match the label text with or without the marker. */
const lbl = (name: string) => new RegExp(`^${name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}( \\*)?$`);

const bodies: { url: string; body: unknown }[] = [];
server.events.on('request:start', async ({ request }) => {
  if (request.url.includes('/api/admin/') && request.method !== 'GET' && request.method !== 'DELETE') bodies.push({ url: request.url, body: await request.clone().json() });
});
beforeEach(() => {
  bodies.length = 0;
});

describe('AdminPage – gating', () => {
  it('redirects home unless reporting=NEW (admin is promoted with the reporting flag)', async () => {
    renderAdminAs(executive, '/admin', 'LEGACY');
    expect(await screen.findByRole('heading', { name: 'Home' })).toBeInTheDocument();
  });

  it('redirects home without ADMIN:VIEW / LEAVE:ADMIN', async () => {
    renderAdminAs(staff);
    expect(await screen.findByRole('heading', { name: 'Home' })).toBeInTheDocument();
  });

  it('renders all thirteen tabs and opens Departments by default', async () => {
    renderAdminAs(executive);
    await screen.findByRole('heading', { name: 'Administration' });
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual(['Departments', 'Job grades', 'Job titles', 'Locations', 'Leave types', 'System parameters', 'Holidays', 'Pay elements', 'Tax brackets', 'Roles', 'Users', 'Leave jobs', 'Audit log']);
    expect(await screen.findByRole('table', { name: 'Departments' })).toBeInTheDocument();
  });
});

describe('Reference data – departments', () => {
  it('lists active departments, hides mutation buttons without ADMIN:EDIT and toggles inactive rows', async () => {
    const user = userEvent.setup();
    renderAdminAs(manager, '/admin/departments');
    const table = await screen.findByRole('table', { name: 'Departments' });
    expect(within(table).getAllByRole('row')).toHaveLength(5);
    expect(screen.queryByRole('button', { name: /^New/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Edit/ })).not.toBeInTheDocument();
    await user.click(screen.getByLabelText('Include inactive'));
    await waitFor(() => expect(within(screen.getByRole('table', { name: 'Departments' })).getAllByRole('row')).toHaveLength(6));
    expect(screen.getByText('Closed Division').closest('tr')).toHaveAttribute('data-inactive', 'true');
  });

  it('creates a department through the generated DepartmentRequest schema (required-field error client side)', async () => {
    const user = userEvent.setup();
    renderAdminAs(executive, '/admin/departments');
    await screen.findByRole('table', { name: 'Departments' });
    await user.click(screen.getByRole('button', { name: 'New department' }));
    const dialog = screen.getByRole('dialog', { name: 'New department' });
    await user.click(within(dialog).getByRole('button', { name: 'Create' }));
    expect(within(dialog).getAllByRole('alert').length).toBeGreaterThan(0);
    expect(bodies).toHaveLength(0);

    await user.type(within(dialog).getByLabelText(lbl('Code')), 'OPS');
    await user.type(within(dialog).getByLabelText(lbl('Name')), 'Operations');
    await user.click(within(dialog).getByRole('button', { name: 'Create' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(await screen.findByText('Operations')).toBeInTheDocument();
    expect(bodies).toHaveLength(1);
    expect(Object.keys(bodies[0].body as object).some((k) => /changedBy|requestedBy|actor/i.test(k))).toBe(false);
  });

  it('maps -20601 duplicate code onto the Code field', async () => {
    const user = userEvent.setup();
    renderAdminAs(executive, '/admin/departments');
    await screen.findByRole('table', { name: 'Departments' });
    await user.click(screen.getByRole('button', { name: 'New department' }));
    const dialog = screen.getByRole('dialog');
    await user.type(within(dialog).getByLabelText(lbl('Code')), 'ENG');
    await user.type(within(dialog).getByLabelText(lbl('Name')), 'Dup');
    await user.click(within(dialog).getByRole('button', { name: 'Create' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(/already exists/);
    expect(within(dialog).getByLabelText(lbl('Code'))).toHaveAttribute('aria-invalid', 'true');
  });

  it('makes the code immutable on edit and deactivates (soft delete) instead of removing', async () => {
    const user = userEvent.setup();
    renderAdminAs(executive, '/admin/departments');
    await screen.findByRole('table', { name: 'Departments' });
    await user.click(screen.getByRole('button', { name: 'Edit QA – Quality Assurance' }));
    const dialog = screen.getByRole('dialog', { name: 'Edit QA – Quality Assurance' });
    expect(within(dialog).getByLabelText(lbl('Code'))).toHaveAttribute('readonly');
    await user.clear(within(dialog).getByLabelText(lbl('Name')));
    await user.type(within(dialog).getByLabelText(lbl('Name')), 'Quality');
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(await screen.findByText('Quality')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Deactivate QA – Quality' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Deactivate' }));
    await waitFor(() => expect(within(screen.getByRole('table', { name: 'Departments' })).getAllByRole('row')).toHaveLength(4));
    await user.click(screen.getByLabelText('Include inactive'));
    await waitFor(() => expect(screen.getByText('Quality').closest('tr')).toHaveAttribute('data-inactive', 'true'));
  });

  it('surfaces -20602 (active dependants) as a toast when deactivation is refused', async () => {
    const user = userEvent.setup();
    renderAdminAs(executive, '/admin/departments');
    await screen.findByRole('table', { name: 'Departments' });
    await user.click(screen.getByRole('button', { name: 'Deactivate ENG – Engineering' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Deactivate' }));
    expect(await screen.findByText(/3 active employees/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Deactivate ENG – Engineering' })).toBeInTheDocument();
  });
});

describe('Reference data – other tables', () => {
  it.each([
    ['job-grades', 'Job grades', 'G1 – Grade 1'],
    ['job-titles', 'Job titles', 'CEO – Chief Executive Officer'],
    ['locations', 'Locations', 'HQ – Headquarters'],
    ['leave-types', 'Leave types', 'ANNUAL – Annual Leave'],
  ])('%s lists rows with edit/deactivate actions', async (path, title, label) => {
    renderAdminAs(executive, `/admin/${path}`);
    const table = await screen.findByRole('table', { name: title });
    expect(within(table).getAllByRole('row').length).toBeGreaterThan(1);
    expect(screen.getByRole('button', { name: `Edit ${label}` })).toBeInTheDocument();
  });

  it('job grade salary range rule is server-authoritative: -20603 lands on Maximum salary', async () => {
    const user = userEvent.setup();
    renderAdminAs(executive, '/admin/job-grades');
    await screen.findByRole('table', { name: 'Job grades' });
    await user.click(screen.getByRole('button', { name: 'New job grade' }));
    const dialog = screen.getByRole('dialog');
    await user.type(within(dialog).getByLabelText(lbl('Code')), 'G9');
    await user.type(within(dialog).getByLabelText(lbl('Name')), 'Grade 9');
    await user.type(within(dialog).getByLabelText(lbl('Minimum salary')), '5000');
    await user.type(within(dialog).getByLabelText(lbl('Maximum salary')), '4000');
    await user.click(within(dialog).getByRole('button', { name: 'Create' }));
    expect(await within(dialog).findByRole('alert')).toBeInTheDocument();
    expect(within(dialog).getByLabelText(lbl('Maximum salary'))).toHaveAttribute('aria-invalid', 'true');
  });
});

describe('System parameters', () => {
  it('lists parameters, filters by group, disables edit/delete for non-editable rows (-20606)', async () => {
    const user = userEvent.setup();
    renderAdminAs(executive, '/admin/system-parameters');
    const table = await screen.findByRole('table', { name: 'System parameters' });
    expect(within(table).getAllByRole('row')).toHaveLength(6);
    expect(screen.getByRole('button', { name: 'Edit SECURITY.TOKEN_TTL_MINUTES' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Delete SECURITY.TOKEN_TTL_MINUTES' })).toBeDisabled();
    await user.type(screen.getByLabelText('Group'), 'PAYROLL');
    await waitFor(() => expect(within(screen.getByRole('table', { name: 'System parameters' })).getAllByRole('row')).toHaveLength(2));
  });

  it('updates a value and hard-deletes a parameter after confirmation', async () => {
    const user = userEvent.setup();
    renderAdminAs(executive, '/admin/system-parameters');
    await screen.findByRole('table', { name: 'System parameters' });
    await user.click(screen.getByRole('button', { name: 'Edit HR.MAX_FUTURE_HIRE_DAYS' }));
    const dialog = screen.getByRole('dialog');
    const value = within(dialog).getByLabelText(lbl('Value'));
    await user.clear(value);
    await user.type(value, '120');
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(await screen.findByText('120')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Delete PAYROLL.AUTO_APPROVE' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete' }));
    await waitFor(() => expect(screen.queryByText('AUTO_APPROVE')).not.toBeInTheDocument());
  });

  it('rejects a value that does not parse as the data type via -20603 on the Value field', async () => {
    const user = userEvent.setup();
    renderAdminAs(executive, '/admin/system-parameters');
    await screen.findByRole('table', { name: 'System parameters' });
    await user.click(screen.getByRole('button', { name: 'Edit HR.MAX_FUTURE_HIRE_DAYS' }));
    const dialog = screen.getByRole('dialog');
    const value = within(dialog).getByLabelText(lbl('Value'));
    await user.clear(value);
    await user.type(value, 'abc');
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));
    expect(await within(dialog).findByRole('alert')).toBeInTheDocument();
    expect(value).toHaveAttribute('aria-invalid', 'true');
  });
});

describe('Leave jobs', () => {
  it('disables the run buttons without LEAVE:ADMIN', async () => {
    renderAdminAs(manager, '/admin/leave-jobs');
    expect(await screen.findByText(/requires the LEAVE:ADMIN authority/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Run accrual' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Run carryover' })).toBeDisabled();
  });

  it('starts an accrual run (no actor id in the body), polls the job to COMPLETED and looks it up by id', async () => {
    const user = userEvent.setup();
    renderAdminAs(executive, '/admin/leave-jobs');
    await user.type(await screen.findByLabelText(lbl('Accrual date')), '2024-06-30');
    await user.click(screen.getByRole('button', { name: 'Run accrual' }));
    const detail = await screen.findByTestId('job-detail');
    await waitFor(() => expect(detail).toHaveTextContent('COMPLETED'));
    expect(bodies.filter((b) => b.url.includes('/leave/accrual')).map((b) => b.body)).toEqual([{ accrualDate: '2024-06-30' }]);

    const jobId = within(screen.getByRole('table', { name: 'Batch runs started this session' })).getAllByRole('row')[1].textContent ?? '';
    const runs = screen.getByRole('table', { name: 'Batch runs started this session' });
    expect(runs).toHaveTextContent('ACCRUAL');

    await user.clear(screen.getByLabelText(lbl('Job id')));
    await user.type(screen.getByLabelText(lbl('Job id')), 'does-not-exist');
    await user.click(screen.getByRole('button', { name: 'Look up' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/JOB_NOT_FOUND/);
    expect(jobId).not.toBe('');
  });

  it('runs a carryover and maps -20702 (already running) onto the year field', async () => {
    const user = userEvent.setup();
    server.use(http.post('*/api/admin/leave/carryover', () => HttpResponse.json({ code: '-20702', message: 'Carryover for 2023 is already running', field: 'year', traceId: 't' }, { status: 409 })));
    renderAdminAs(executive, '/admin/leave-jobs');
    const year = await screen.findByLabelText(lbl('Year to close'));
    await user.clear(year);
    await user.type(year, '2023');
    await user.click(screen.getByRole('button', { name: 'Run carryover' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/already running/);
    expect(year).toHaveAttribute('aria-invalid', 'true');
  });
});

describe('Audit log', () => {
  it('searches with the generated AuditLogSearchQuery, expands old/new values and rejects from > to client side', async () => {
    const user = userEvent.setup();
    renderAdminAs(executive, '/admin/audit-log');
    const table = await screen.findByRole('table', { name: 'Audit log' });
    expect(within(table).getAllByRole('row')).toHaveLength(4);

    await user.selectOptions(screen.getByLabelText(lbl('Action')), 'UPDATE');
    await user.click(screen.getByRole('button', { name: 'Search' }));
    await waitFor(() => expect(within(screen.getByRole('table', { name: 'Audit log' })).getAllByRole('row')).toHaveLength(2));
    await user.click(screen.getByRole('button', { expanded: false }));
    expect(screen.getByTestId('audit-values-9003')).toHaveTextContent('"jobId":4');

    await user.type(screen.getByLabelText(lbl('From')), '2024-07-01');
    await user.type(screen.getByLabelText(lbl('To')), '2024-06-01');
    await user.click(screen.getByRole('button', { name: 'Search' }));
    expect(screen.getByLabelText(lbl('To'))).toHaveAttribute('aria-invalid', 'true');
  });

  it('shows the empty state', async () => {
    const user = userEvent.setup();
    renderAdminAs(executive, '/admin/audit-log');
    await screen.findByRole('table', { name: 'Audit log' });
    await user.type(screen.getByLabelText(lbl('Table')), 'nothing');
    await user.click(screen.getByRole('button', { name: 'Search' }));
    expect(await screen.findByText('No audit entries match the selected filters.')).toBeInTheDocument();
  });
});
