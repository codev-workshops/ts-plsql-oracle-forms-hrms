import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { SEED_ACCOUNTS } from '../../../../e2e/seed-accounts';
import { setAccessToken } from '../../../api/http';
import { MOCK_USERS, seedAccessToken } from '../../../mocks/handlers';
import { server } from '../../../mocks/server';
import { renderWithProviders } from '../../../test/render';
import { plusYears, zodFor } from '../../../validation/schema';
import { AdminPage } from '../AdminPage';

const executive = SEED_ACCOUNTS.executive.email; // ADMIN:VIEW + ADMIN:EDIT
const manager = SEED_ACCOUNTS.manager.email; // ADMIN:VIEW only

const routes = (
  <Routes>
    <Route path="/" element={<h1>Home</h1>} />
    <Route path="/admin/*" element={<AdminPage />} />
  </Routes>
);

function renderAdminAs(email: string, path: string) {
  setAccessToken(seedAccessToken(email));
  return renderWithProviders(routes, { initialUser: MOCK_USERS[email], initialEntries: [path], moduleFlags: { reporting: 'NEW' } });
}

const lbl = (name: string) => new RegExp(`^${name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}( \\*)?$`);

const bodies: { url: string; method: string; body: unknown }[] = [];
server.events.on('request:start', async ({ request }) => {
  if (request.url.includes('/api/admin/') && request.method !== 'GET' && request.method !== 'DELETE') bodies.push({ url: request.url, method: request.method, body: await request.clone().json() });
});
beforeEach(() => {
  bodies.length = 0;
});

const today = new Date().toISOString().slice(0, 10);

describe('holiday.dateWindow (generated rule, calendar-year arithmetic)', () => {
  it('plusYears matches LocalDate.plusYears (leap day clamps to 28 Feb)', () => {
    expect(plusYears('2024-02-29', 10)).toBe('2034-02-28');
    expect(plusYears('2024-02-29', 4)).toBe('2028-02-29');
    expect(plusYears('2025-12-31', 10)).toBe('2035-12-31');
  });

  it('accepts [1990-01-01, today+10y] and rejects both sides of the window', () => {
    const schema = zodFor('HolidayRequest');
    const ok = (holidayDate: string) => schema.safeParse({ holidayDate, holidayName: 'X' }).success;
    expect(ok('1990-01-01')).toBe(true);
    expect(ok('1989-12-31')).toBe(false);
    expect(ok(plusYears(today, 10))).toBe(true);
    expect(ok(plusYears(plusYears(today, 10), 1))).toBe(false);
  });
});

describe('Holidays', () => {
  it('lists active holidays for ADMIN:VIEW with no write controls', async () => {
    renderAdminAs(manager, '/admin/holidays');
    const table = await screen.findByRole('table', { name: 'Holidays' });
    expect(within(table).getAllByRole('row').length).toBeGreaterThan(1);
    expect(screen.getByText('Juneteenth')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^New/ })).not.toBeInTheDocument();
  });

  it('creates a company-wide holiday, refreshes the grid and keeps the actor out of the body', async () => {
    const user = userEvent.setup();
    renderAdminAs(executive, '/admin/holidays');
    await screen.findByRole('table', { name: 'Holidays' });
    await user.click(screen.getByRole('button', { name: 'New holiday' }));
    const dialog = screen.getByRole('dialog', { name: 'New holiday' });
    await user.type(within(dialog).getByLabelText(lbl('Date')), '1980-07-04');
    await user.type(within(dialog).getByLabelText(lbl('Name')), 'Too early');
    await user.click(within(dialog).getByRole('button', { name: 'Create' }));
    expect(within(dialog).getAllByRole('alert').length).toBeGreaterThan(0);
    expect(bodies).toHaveLength(0);

    await user.clear(within(dialog).getByLabelText(lbl('Date')));
    await user.type(within(dialog).getByLabelText(lbl('Date')), '2025-07-04');
    await user.clear(within(dialog).getByLabelText(lbl('Name')));
    await user.type(within(dialog).getByLabelText(lbl('Name')), 'Independence Day');
    await user.click(within(dialog).getByRole('button', { name: 'Create' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(await screen.findByText('Independence Day')).toBeInTheDocument();
    expect(bodies).toHaveLength(1);
    expect(bodies[0].body).toMatchObject({ holidayDate: '2025-07-04', holidayName: 'Independence Day', activeFlag: true });
    expect(Object.keys(bodies[0].body as object).some((k) => /changedBy|createdBy|actor|userId/i.test(k))).toBe(false);
  });

  it('maps a duplicate date/location (-20601) onto the Date field', async () => {
    const user = userEvent.setup();
    renderAdminAs(executive, '/admin/holidays');
    await screen.findByRole('table', { name: 'Holidays' });
    await user.click(screen.getByRole('button', { name: 'New holiday' }));
    const dialog = screen.getByRole('dialog');
    await user.type(within(dialog).getByLabelText(lbl('Date')), '2024-01-01');
    await user.type(within(dialog).getByLabelText(lbl('Name')), 'Dup');
    await user.click(within(dialog).getByRole('button', { name: 'Create' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(/already exists/);
  });
});

describe('Pay elements', () => {
  it('marks reserved rows, allows editing their name but never deactivating them', async () => {
    const user = userEvent.setup();
    renderAdminAs(executive, '/admin/pay-elements');
    await screen.findByRole('table', { name: 'Pay elements' });
    expect(screen.getByText('BASE_PAY (reserved)')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Deactivate BASE_PAY/ })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^Deactivate BONUS/ })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /^Edit FED_TAX/ }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByLabelText(lbl('Code'))).toHaveAttribute('readonly');
    await user.selectOptions(within(dialog).getByLabelText(lbl('Calculation')), 'FLAT');
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(/reserved/);
    expect(within(dialog).getByLabelText(lbl('Calculation'))).toHaveAttribute('aria-invalid', 'true');
  });

  it('creates an EARNING element with a fixed-scale default amount and refreshes', async () => {
    const user = userEvent.setup();
    renderAdminAs(executive, '/admin/pay-elements');
    await screen.findByRole('table', { name: 'Pay elements' });
    await user.click(screen.getByRole('button', { name: 'New pay element' }));
    const dialog = screen.getByRole('dialog', { name: 'New pay element' });
    await user.type(within(dialog).getByLabelText(lbl('Code')), 'SHIFT_DIFF');
    await user.type(within(dialog).getByLabelText(lbl('Name')), 'Shift differential');
    await user.selectOptions(within(dialog).getByLabelText(lbl('Type')), 'EARNING');
    await user.selectOptions(within(dialog).getByLabelText(lbl('Calculation')), 'FLAT');
    await user.type(within(dialog).getByLabelText(lbl('Default amount')), '2.5');
    await user.click(within(dialog).getByRole('button', { name: 'Create' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(await screen.findByText('Shift differential')).toBeInTheDocument();
    expect(bodies[0].body).toMatchObject({ elementCode: 'SHIFT_DIFF', defaultAmount: '2.50' });
  });
});

describe('Tax brackets', () => {
  it('hides write controls for locked years and reports ladder gaps', async () => {
    const user = userEvent.setup();
    renderAdminAs(executive, '/admin/tax-brackets');
    await screen.findByRole('table', { name: 'Tax brackets' });
    expect(screen.getAllByText(/Locked by an approved payroll run/).length).toBeGreaterThan(0);
    expect(screen.queryByRole('button', { name: /^Edit 2023/ })).not.toBeInTheDocument();
    await user.clear(screen.getByLabelText('Tax year'));
    await user.type(screen.getByLabelText('Tax year'), '2024');
    const gaps = await screen.findByTestId('ladder-gaps');
    expect(gaps).toHaveTextContent('[50000.00, 60000.00)');
    expect(gaps).toHaveTextContent('MISSING_TAX_RATE');
  });

  it('maps an overlapping bracket (-20608) onto the minimum field and a filled gap clears the ladder report', async () => {
    const user = userEvent.setup();
    renderAdminAs(executive, '/admin/tax-brackets');
    await screen.findByRole('table', { name: 'Tax brackets' });
    await user.clear(screen.getByLabelText('Tax year'));
    await user.type(screen.getByLabelText('Tax year'), '2024');
    await screen.findByTestId('ladder-gaps');

    await user.click(screen.getByRole('button', { name: 'New tax bracket' }));
    const dialog = screen.getByRole('dialog', { name: 'New tax bracket' });
    await user.type(within(dialog).getByLabelText(lbl('Tax year')), '2024');
    await user.selectOptions(within(dialog).getByLabelText(lbl('Filing status')), 'SINGLE');
    await user.type(within(dialog).getByLabelText(lbl('Bracket minimum')), '45000');
    await user.type(within(dialog).getByLabelText(lbl('Bracket maximum')), '60000');
    await user.type(within(dialog).getByLabelText(lbl('Rate')), '0.22');
    await user.click(within(dialog).getByRole('button', { name: 'Create' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(/overlaps/);

    await user.clear(within(dialog).getByLabelText(lbl('Bracket minimum')));
    await user.type(within(dialog).getByLabelText(lbl('Bracket minimum')), '50000');
    await user.click(within(dialog).getByRole('button', { name: 'Create' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(screen.getByTestId('ladder-gaps')).not.toHaveTextContent('50000.00'));
    expect(bodies.at(-1)?.body).toMatchObject({ bracketMin: '50000.00', bracketMax: '60000.00', taxRate: '0.2200' });
  });
});

describe('Roles', () => {
  it('keeps seeded roles read-only and validates the permissions multi-select client side', async () => {
    const user = userEvent.setup();
    renderAdminAs(executive, '/admin/roles');
    await screen.findByRole('table', { name: 'Roles' });
    expect(screen.getByRole('button', { name: 'Edit EXECUTIVE' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Delete STAFF' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Delete AUDITOR' })).toBeEnabled();

    await user.click(screen.getByRole('button', { name: 'New role' }));
    const dialog = screen.getByRole('dialog', { name: 'New role' });
    await user.type(within(dialog).getByLabelText(lbl('Code')), 'PAYROLL_CLERK');
    await user.type(within(dialog).getByLabelText(lbl('Name')), 'Payroll clerk');
    await user.type(within(dialog).getByLabelText(lbl('Minimum grade')), '2');
    await user.type(within(dialog).getByLabelText(lbl('Maximum grade')), '5');
    await user.click(within(dialog).getByRole('button', { name: 'Create' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(/at least one permission/i);
    expect(bodies).toHaveLength(0);

    await user.click(within(dialog).getByRole('checkbox', { name: 'PAYROLL:VIEW' }));
    await user.click(within(dialog).getByRole('checkbox', { name: 'PAYROLL:EDIT' }));
    await user.click(within(dialog).getByRole('button', { name: 'Create' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(await screen.findByText('PAYROLL_CLERK')).toBeInTheDocument();
    expect(bodies[0].body).toMatchObject({ roleCode: 'PAYROLL_CLERK', permissions: ['PAYROLL:VIEW', 'PAYROLL:EDIT'] });
  });

  it('shows sessionsRevoked after a role update and maps -20801 duplicate codes', async () => {
    const user = userEvent.setup();
    renderAdminAs(executive, '/admin/roles');
    await screen.findByRole('table', { name: 'Roles' });
    await user.click(screen.getByRole('button', { name: 'Edit AUDITOR' }));
    const dialog = screen.getByRole('dialog', { name: 'Edit role AUDITOR' });
    await user.click(within(dialog).getByRole('checkbox', { name: 'LEAVE:VIEW' }));
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));
    expect(await screen.findByText(/AUDITOR saved · 0 session\(s\) revoked/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'New role' }));
    const create = screen.getByRole('dialog', { name: 'New role' });
    await user.type(within(create).getByLabelText(lbl('Code')), 'AUDITOR');
    await user.type(within(create).getByLabelText(lbl('Name')), 'Dup');
    await user.type(within(create).getByLabelText(lbl('Minimum grade')), '1');
    await user.type(within(create).getByLabelText(lbl('Maximum grade')), '1');
    await user.click(within(create).getByRole('checkbox', { name: 'REPORTS:VIEW' }));
    await user.click(within(create).getByRole('button', { name: 'Create' }));
    expect(await within(create).findByRole('alert')).toHaveTextContent(/already exists/);
  });
});

describe('Users', () => {
  it('shows effective authorities, blocks self-modification and validates roleIds before the request', async () => {
    const user = userEvent.setup();
    renderAdminAs(executive, '/admin/users');
    const table = await screen.findByRole('table', { name: 'User accounts' });
    const parkRow = within(table).getByText('jennifer.park@company.com').closest('tr')!;
    expect(within(parkRow).getByRole('list', { name: /Effective authorities/ })).toHaveTextContent('ADMIN:VIEW');
    expect(within(parkRow).getByRole('list', { name: /Effective authorities/ })).not.toHaveTextContent('ADMIN:EDIT');
    expect(screen.getByRole('button', { name: 'Assign roles to james.richardson@company.com' })).toBeDisabled();

    await user.click(within(parkRow).getByRole('button', { name: /^Assign roles/ }));
    const dialog = screen.getByRole('dialog', { name: /Roles for jennifer.park/ });
    await user.click(within(dialog).getByRole('checkbox', { name: /^MANAGER/ }));
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(/at least one role/i);
    expect(bodies).toHaveLength(0);

    await user.click(within(dialog).getByRole('checkbox', { name: /^MANAGER/ }));
    await user.click(within(dialog).getByRole('checkbox', { name: /^AUDITOR/ }));
    expect(within(dialog).getByTestId('authority-preview')).toHaveTextContent('REPORTS:VIEW');
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));
    expect(await screen.findByText(/jennifer.park@company.com saved · 1 session\(s\) revoked/)).toBeInTheDocument();
    expect(bodies[0].body).toEqual({ roleIds: [2, 1000] });
    await waitFor(() => expect(within(screen.getByRole('table', { name: 'User accounts' })).getByText('MANAGER, AUDITOR')).toBeInTheDocument());
  });

  it('disables an account through UserStatusRequest and refreshes the status badge', async () => {
    const user = userEvent.setup();
    renderAdminAs(executive, '/admin/users');
    const table = await screen.findByRole('table', { name: 'User accounts' });
    const row = within(table).getByText('david.martinez@company.com').closest('tr')!;
    await user.click(within(row).getByRole('button', { name: /^Change status/ }));
    const dialog = screen.getByRole('dialog', { name: /Change status of david.martinez/ });
    expect(within(dialog).getByLabelText(lbl('Status'))).toHaveValue('DISABLED');
    await user.type(within(dialog).getByLabelText(lbl('Reason')), 'Contract ended');
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));
    expect(await screen.findByText(/david.martinez@company.com saved · 1 session\(s\) revoked/)).toBeInTheDocument();
    expect(bodies[0].body).toEqual({ status: 'DISABLED', reason: 'Contract ended' });
    await waitFor(() => expect(within(within(screen.getByRole('table', { name: 'User accounts' })).getByText('david.martinez@company.com').closest('tr')!).getByText('Disabled')).toBeInTheDocument());
  });

  it('hides role/status controls without ADMIN:EDIT', async () => {
    renderAdminAs(manager, '/admin/users');
    await screen.findByRole('table', { name: 'User accounts' });
    expect(screen.queryByRole('button', { name: /^Assign roles/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Change status/ })).not.toBeInTheDocument();
  });
});
