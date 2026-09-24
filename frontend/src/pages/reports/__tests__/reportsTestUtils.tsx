import { Route, Routes } from 'react-router-dom';
import { setAccessToken } from '../../../api/http';
import type { ModuleFlagValue } from '../../../app/modules';
import { MOCK_USERS, seedAccessToken } from '../../../mocks/handlers';
import { renderWithProviders } from '../../../test/render';
import { ReportsPage } from '../ReportsPage';

export const reportsRoutes = (
  <Routes>
    <Route path="/" element={<h1>Home</h1>} />
    <Route path="/reports/*" element={<ReportsPage />} />
  </Routes>
);

export function renderReportsAs(email: string, path = '/reports', flag: ModuleFlagValue = 'NEW') {
  setAccessToken(seedAccessToken(email));
  return renderWithProviders(reportsRoutes, { initialUser: MOCK_USERS[email], initialEntries: [path], moduleFlags: { reporting: flag } });
}
