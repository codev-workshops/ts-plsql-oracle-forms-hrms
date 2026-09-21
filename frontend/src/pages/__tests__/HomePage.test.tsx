import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { MOCK_USERS } from '../../mocks/handlers';
import { renderWithProviders } from '../../test/render';
import { SEED_ACCOUNTS } from '../../../e2e/seed-accounts';
import { HomePage } from '../HomePage';

function renderHome(email: string) {
  return renderWithProviders(
    <Routes>
      <Route path="/" element={<HomePage />} />
      <Route path="*" element={<div data-testid="navigated-away" />} />
    </Routes>,
    { initialUser: MOCK_USERS[email] },
  );
}

describe('HomePage', () => {
  it('shows every authorised module tile for an admin, legacy ones disabled (P0-D1)', () => {
    renderHome(SEED_ACCOUNTS.executive.email);
    expect(screen.getByRole('heading', { name: 'Welcome, JAMES RICHARDSON' })).toBeInTheDocument();
    const tiles = screen.getAllByRole('listitem');
    expect(tiles.map((t) => t.getAttribute('data-testid'))).toEqual([
      'tile-employees',
      'tile-payroll',
      'tile-leave',
      'tile-performance',
    ]);
    for (const t of tiles) {
      expect(t).toHaveAttribute('data-legacy', 'true');
      expect(t).toHaveAttribute('aria-disabled', 'true');
      expect(t).not.toHaveAttribute('href');
      expect(t.closest('a')).toBeNull();
      expect(t).toHaveTextContent('Not available in this environment');
      expect(t).not.toHaveTextContent('Opens in Oracle Forms');
    }
    // Reports/Admin have no recoverable source until Phase 5 – hidden even with the authority.
    expect(screen.queryByTestId('tile-reports')).not.toBeInTheDocument();
    expect(screen.queryByTestId('tile-admin')).not.toBeInTheDocument();
  });

  it('does not navigate when a disabled legacy tile is clicked (P0-D1)', async () => {
    const user = userEvent.setup();
    renderHome(SEED_ACCOUNTS.executive.email);
    await user.click(screen.getByTestId('tile-payroll'));
    expect(screen.queryByTestId('navigated-away')).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Welcome, JAMES RICHARDSON' })).toBeInTheDocument();
  });

  it('hides PAYROLL for a user without PAYROLL:VIEW', () => {
    renderHome(SEED_ACCOUNTS.staff.email);
    expect(screen.queryByTestId('tile-payroll')).not.toBeInTheDocument();
    expect(screen.getByTestId('tile-employees')).toBeInTheDocument();
    expect(screen.getByTestId('tile-leave')).toBeInTheDocument();
    expect(screen.getByTestId('tile-performance')).toBeInTheDocument();
  });
});
