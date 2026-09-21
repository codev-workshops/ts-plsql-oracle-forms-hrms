import { screen } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { MOCK_USERS } from '../../mocks/handlers';
import { renderWithProviders } from '../../test/render';
import { SEED_ACCOUNTS } from '../../../e2e/seed-accounts';
import { ForbiddenPage } from '../ForbiddenPage';

describe('ForbiddenPage', () => {
  it('renders the access-denied message with a link home', () => {
    renderWithProviders(
      <Routes>
        <Route path="/forbidden" element={<ForbiddenPage />} />
      </Routes>,
      { initialUser: MOCK_USERS[SEED_ACCOUNTS.staff.email], initialEntries: ['/forbidden'] },
    );
    expect(screen.getByRole('heading')).toHaveTextContent(/access denied/i);
    expect(screen.getByRole('link')).toHaveAttribute('href', '/');
  });
});
