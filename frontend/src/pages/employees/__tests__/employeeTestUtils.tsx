import { Route, Routes } from 'react-router-dom';
import { afterEach } from 'vitest';
import { SEED_ACCOUNTS } from '../../../../e2e/seed-accounts';
import { setAccessToken } from '../../../api/http';
import type { ModuleFlagValue } from '../../../app/modules';
import { setMockEmployeeModuleFlag } from '../../../mocks/employeeHandlers';
import { MOCK_USERS, seedAccessToken } from '../../../mocks/handlers';
import { renderWithProviders } from '../../../test/render';
import { EmployeePage } from '../EmployeePage';

export const EXEC = SEED_ACCOUNTS.executive.email; // empId 1, EMPLOYEE:EDIT + PAYROLL:EDIT
export const MANAGER = SEED_ACCOUNTS.manager.email; // empId 21, EMPLOYEE:VIEW + PAYROLL:VIEW
export const STAFF = SEED_ACCOUNTS.staff.email; // empId 11, EMPLOYEE:VIEW

export function renderEmployeesAs(email: string, path = '/employees', flag: ModuleFlagValue = 'NEW') {
  setMockEmployeeModuleFlag(flag);
  setAccessToken(seedAccessToken(email));
  return renderWithProviders(
    <Routes>
      <Route path="/employees/*" element={<EmployeePage flags={{ employee: flag }} />} />
    </Routes>,
    { initialUser: MOCK_USERS[email], initialEntries: [path] },
  );
}

/** src/test/setup.ts owns msw + mock-state reset; this only restores the module flag. */
export function resetModuleFlagAfterEach() {
  afterEach(() => setMockEmployeeModuleFlag('NEW'));
}

export function iso(offsetDays: number): string {
  const d = new Date();
  d.setDate(d.getDate() + offsetDays);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}
