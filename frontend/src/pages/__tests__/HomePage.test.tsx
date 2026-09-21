import { screen } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { MOCK_USERS } from '../../mocks/handlers';
import { renderWithProviders } from '../../test/render';
import { HomePage } from '../HomePage';

function renderHome(email: string) {
  return renderWithProviders(
    <Routes>
      <Route path="/" element={<HomePage />} />
    </Routes>,
    { initialUser: MOCK_USERS[email] },
  );
}

describe('HomePage', () => {
  it('shows every authorised module tile for an admin, legacy ones linking through the proxy', () => {
    renderHome('admin@hrms.example');
    expect(screen.getByRole('heading', { name: 'Welcome, Ada Admin' })).toBeInTheDocument();
    const tiles = screen.getAllByRole('listitem');
    expect(tiles.map((t) => t.getAttribute('data-testid'))).toEqual([
      'tile-employees',
      'tile-payroll',
      'tile-leave',
      'tile-performance',
    ]);
    for (const t of tiles) {
      expect(t).toHaveAttribute('data-legacy', 'true');
      expect(t).toHaveTextContent('Opens in Oracle Forms');
    }
    expect(screen.getByTestId('tile-payroll')).toHaveAttribute('href', '/payroll');
    // Reports/Admin have no recoverable source until Phase 5 – hidden even with the authority.
    expect(screen.queryByTestId('tile-reports')).not.toBeInTheDocument();
    expect(screen.queryByTestId('tile-admin')).not.toBeInTheDocument();
  });

  it('hides PAYROLL for a user without PAYROLL:VIEW', () => {
    renderHome('staff@hrms.example');
    expect(screen.queryByTestId('tile-payroll')).not.toBeInTheDocument();
    expect(screen.getByTestId('tile-employees')).toBeInTheDocument();
    expect(screen.getByTestId('tile-leave')).toBeInTheDocument();
    expect(screen.getByTestId('tile-performance')).toBeInTheDocument();
  });
});
