import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HttpResponse, http } from 'msw';
import { useState } from 'react';
import { SEED_ACCOUNTS } from '../../../e2e/seed-accounts';
import { setAccessToken } from '../../api/http';
import { MOCK_USERS, seedAccessToken } from '../../mocks/handlers';
import { server } from '../../mocks/server';
import { renderWithProviders } from '../../test/render';
import { ReferenceDropdown, type ReferenceSource } from '../ReferenceDropdown';

function Harness(props: { source: ReferenceSource; includeInactive?: boolean; excludeSelf?: boolean }) {
  const [value, setValue] = useState<string | null>(null);
  return (
    <>
      <ReferenceDropdown {...props} label="Ref" value={value} onChange={setValue} />
      <output data-testid="value">{value ?? ''}</output>
    </>
  );
}

function renderAs(email: string, ui: React.ReactElement) {
  setAccessToken(seedAccessToken(email));
  return renderWithProviders(ui, { initialUser: MOCK_USERS[email] });
}

describe('ReferenceDropdown', () => {
  it('loads active departments from GET /api/reference/departments and reports selection', async () => {
    renderAs(SEED_ACCOUNTS.staff.email, <Harness source="departments" />);
    const select = await screen.findByRole('combobox', { name: 'Ref' });
    await waitFor(() => expect(select).toBeEnabled());
    const labels = screen.getAllByRole('option').map((o) => o.textContent);
    expect(labels).toEqual(['— Select —', 'HR – Human Resources', 'FIN – Finance', 'ENG – Engineering']);

    await userEvent.setup().selectOptions(select, '2');
    expect(screen.getByTestId('value')).toHaveTextContent('2');
  });

  it('includes inactive rows when includeInactive is set', async () => {
    renderAs(SEED_ACCOUNTS.staff.email, <Harness source="departments" includeInactive />);
    await waitFor(() => expect(screen.getByRole('option', { name: 'OLD – Retired Dept' })).toBeInTheDocument());
  });

  it('shows each contract reference source with its label mapping', async () => {
    renderAs(SEED_ACCOUNTS.staff.email, <Harness source="job-titles" />);
    await waitFor(() => expect(screen.getByRole('option', { name: 'Analyst (G3)' })).toBeInTheDocument());
  });

  it('uses GET /api/employees for managers, searchable, excluding the caller', async () => {
    renderAs(SEED_ACCOUNTS.staff.email, <Harness source="managers" excludeSelf />);
    await waitFor(() => expect(screen.getByRole('option', { name: 'JAMES RICHARDSON – CEO' })).toBeInTheDocument());
    expect(screen.queryByRole('option', { name: /DAVID MARTINEZ/ })).not.toBeInTheDocument();

    await userEvent.setup().type(screen.getByRole('searchbox', { name: 'Search Ref' }), 'mia');
    await waitFor(() => expect(screen.getByRole('option', { name: 'MIA MANAGER' })).toBeInTheDocument());
    expect(screen.queryByRole('option', { name: /JAMES RICHARDSON/ })).not.toBeInTheDocument();
  });

  it('surfaces a load error with a retry', async () => {
    server.use(
      http.get('/api/reference/locations', () =>
        HttpResponse.json({ code: 'INTERNAL_ERROR', message: 'An unexpected error occurred', traceId: 'x' }, { status: 500 }),
      ),
    );
    renderAs(SEED_ACCOUNTS.staff.email, <Harness source="locations" />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load ref.');
    server.resetHandlers();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(screen.getByRole('option', { name: 'Headquarters' })).toBeInTheDocument());
  });
});
