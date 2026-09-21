/**
 * Mirrors tools/fixtures/pg/04_user_accounts.sql (+ 02_employee_data.sql names,
 * V2__p0_auth_foundation.sql role bands); keep in sync.
 */
import type { Authority } from '../src/api/types';

export const SEED_PASSWORD = 'Welcome1!';

export interface SeedAccount {
  email: string;
  displayName: string;
  empId: number;
  empNumber: string;
  firstName: string;
  lastName: string;
  role: 'STAFF' | 'MANAGER' | 'EXECUTIVE';
  mustChangePassword: boolean;
}

export const SEED_ACCOUNTS = {
  executive: {
    email: 'james.richardson@company.com',
    displayName: 'JAMES RICHARDSON',
    empId: 1,
    empNumber: 'EMP-000001',
    firstName: 'JAMES',
    lastName: 'RICHARDSON',
    role: 'EXECUTIVE',
    mustChangePassword: false,
  },
  manager: {
    email: 'jennifer.park@company.com',
    displayName: 'JENNIFER PARK',
    empId: 21,
    empNumber: 'EMP-000021',
    firstName: 'JENNIFER',
    lastName: 'PARK',
    role: 'MANAGER',
    mustChangePassword: false,
  },
  staff: {
    email: 'david.martinez@company.com',
    displayName: 'DAVID MARTINEZ',
    empId: 11,
    empNumber: 'EMP-000011',
    firstName: 'DAVID',
    lastName: 'MARTINEZ',
    role: 'STAFF',
    mustChangePassword: false,
  },
  firstLogin: {
    email: 'emily.johnson@company.com',
    displayName: 'EMILY JOHNSON',
    empId: 12,
    empNumber: 'EMP-000012',
    firstName: 'EMILY',
    lastName: 'JOHNSON',
    role: 'STAFF',
    mustChangePassword: true,
  },
} as const satisfies Record<string, SeedAccount>;

const MODULES = ['PAYROLL', 'EMPLOYEE', 'LEAVE', 'ADMIN', 'REPORTS'] as const;
const ACTIONS = ['VIEW', 'EDIT', 'APPROVE', 'CREATE'] as const;

export const ROLE_AUTHORITIES: Record<SeedAccount['role'], Authority[]> = {
  STAFF: ['EMPLOYEE:VIEW', 'LEAVE:VIEW', 'LEAVE:CREATE'],
  MANAGER: ['PAYROLL:VIEW', 'EMPLOYEE:VIEW', 'LEAVE:VIEW', 'ADMIN:VIEW', 'REPORTS:VIEW', 'LEAVE:CREATE'],
  EXECUTIVE: MODULES.flatMap((module) => ACTIONS.map((action) => `${module}:${action}` as Authority)),
};
