import { Route, Routes } from 'react-router-dom';
import { setAccessToken } from '../../../api/http';
import type { ModuleFlagValue } from '../../../app/modules';
import { MOCK_USERS, seedAccessToken } from '../../../mocks/handlers';
import { renderWithProviders } from '../../../test/render';
import { PayrollPage } from '../PayrollPage';

export const payrollRoutes = (
  <Routes>
    <Route path="/" element={<h1>Home</h1>} />
    <Route path="/payroll/*" element={<PayrollPage />} />
  </Routes>
);

export function renderPayrollAs(email: string, path = '/payroll', flag: ModuleFlagValue = 'NEW') {
  setAccessToken(seedAccessToken(email));
  return renderWithProviders(payrollRoutes, { initialUser: MOCK_USERS[email], initialEntries: [path], moduleFlags: { payroll: flag, 'payroll.engine': 'JAVA' } });
}
