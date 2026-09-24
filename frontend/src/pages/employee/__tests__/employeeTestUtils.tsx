import { Route, Routes } from 'react-router-dom';
import { setAccessToken } from '../../../api/http';
import type { ModuleFlagValue } from '../../../app/modules';
import { MOCK_USERS, seedAccessToken } from '../../../mocks/handlers';
import { renderWithProviders } from '../../../test/render';
import { EmployeePage } from '../EmployeePage';

export const employeeRoutes = (
  <Routes>
    <Route path="/employees/*" element={<EmployeePage />} />
  </Routes>
);

export function renderEmployeesAs(email: string, path = '/employees', flag: ModuleFlagValue = 'NEW') {
  setAccessToken(seedAccessToken(email));
  return renderWithProviders(employeeRoutes, { initialUser: MOCK_USERS[email], initialEntries: [path], moduleFlags: { employee: flag } });
}

export function iso(offsetDays: number, from = new Date()): string {
  const d = new Date(from.getFullYear(), from.getMonth(), from.getDate() + offsetDays);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}
