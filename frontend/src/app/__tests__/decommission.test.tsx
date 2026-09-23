import { screen, within } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { SEED_ACCOUNTS } from '../../../e2e/seed-accounts';
import { MOCK_USERS } from '../../mocks/handlers';
import { HomePage } from '../../pages/HomePage';
import { renderWithProviders } from '../../test/render';
import { AppShell } from '../AppShell';
import { allModulesNew, isDecommissioned, visibleTiles, type ModuleFlagValue } from '../modules';

const ALL_NEW: Record<string, ModuleFlagValue> = { employee: 'NEW', payroll: 'NEW', leave: 'NEW', performance: 'NEW', reporting: 'NEW' };
const executive = MOCK_USERS[SEED_ACCOUNTS.executive.email];

function renderShell(flags: Record<string, ModuleFlagValue>) {
  return renderWithProviders(
    <Routes>
      <Route element={<AppShell />}>
        <Route index element={<HomePage />} />
      </Route>
    </Routes>,
    { initialUser: executive, moduleFlags: flags },
  );
}

describe('Forms decommission switch (CUTOVER_PLAN.md §9.3)', () => {
  it('requires decommission=NEW AND every switchable module NEW', () => {
    expect(allModulesNew(ALL_NEW)).toBe(true);
    expect(isDecommissioned(ALL_NEW)).toBe(false);
    expect(isDecommissioned({ ...ALL_NEW, decommission: 'NEW' })).toBe(true);
    expect(isDecommissioned({ ...ALL_NEW, performance: 'NEW_READONLY', decommission: 'NEW' })).toBe(false);
    expect(isDecommissioned({ ...ALL_NEW, payroll: 'LEGACY', decommission: 'NEW' })).toBe(false);
    expect(isDecommissioned({ decommission: 'NEW' })).toBe(false);
  });

  it('keeps legacy tiles (SSO bridge entry points) while any module is still LEGACY, even with decommission=NEW', () => {
    const flags: Record<string, ModuleFlagValue> = { ...ALL_NEW, payroll: 'LEGACY', decommission: 'NEW' };
    expect(visibleTiles(executive.roles, flags).find((t) => t.id === 'payroll')?.promoted).toBe(false);
    renderShell(flags);
    const nav = screen.getByRole('navigation', { name: 'Modules' });
    expect(within(nav).getByText('Payroll')).toHaveAttribute('data-legacy', 'true');
    expect(screen.getByTestId('tile-payroll')).toHaveAttribute('data-legacy', 'true');
    expect(screen.getByRole('banner')).not.toHaveAttribute('data-decommissioned');
  });

  it('removes every legacy tile and SSO bridge link once all flags are NEW and decommission=NEW', () => {
    renderShell({ ...ALL_NEW, decommission: 'NEW' });
    const nav = screen.getByRole('navigation', { name: 'Modules' });
    expect(within(nav).getAllByRole('link').map((l) => l.textContent)).toEqual(['Employees', 'Payroll', 'Leave', 'Performance', 'Reports', 'Administration']);
    expect(document.querySelectorAll('[data-legacy]')).toHaveLength(0);
    expect(screen.getByRole('banner')).toHaveAttribute('data-decommissioned', 'true');
    expect(screen.queryByText('Not available in this environment')).not.toBeInTheDocument();
  });

  it('shows Reports and Administration tiles only once reporting=NEW (admin is promoted with reporting)', () => {
    expect(visibleTiles(executive.roles, { reporting: 'LEGACY' }).map((t) => t.id)).not.toContain('reports');
    const ids = visibleTiles(executive.roles, { reporting: 'NEW' }).map((t) => t.id);
    expect(ids).toEqual(expect.arrayContaining(['reports', 'admin']));
  });
});
