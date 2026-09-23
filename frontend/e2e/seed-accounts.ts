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
  /** STAFF whose `manager_id` is the executive (emp 1): the only seeded reviewee/reviewer pair with two logins. */
  staffOfExecutive: {
    email: 'sarah.chen@company.com',
    displayName: 'SARAH CHEN',
    empId: 2,
    empNumber: 'EMP-000002',
    firstName: 'SARAH',
    lastName: 'CHEN',
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

/**
 * Mirrors the REVIEW_CYCLES / PERFORMANCE_REVIEWS block of tools/fixtures/pg/03_transaction_data.sql
 * (+ 02_employee_data.sql names); keep in sync. These rows also feed VW_PENDING_APPROVALS in
 * tests/golden/views-baseline.csv, so real-stack specs must only READ them.
 */
export const PG_PERFORMANCE_FIXTURES = {
  cycle: { cycleId: 9001, cycleName: '2024 Mid-Year Review', status: 'IN_PROGRESS' },
  reviews: [
    { reviewId: 5001, empId: 22, employeeName: 'THOMAS BAKER', reviewerEmpId: 21, status: 'MANAGER_REVIEW' },
    { reviewId: 5003, empId: 23, employeeName: 'LISA WONG', reviewerEmpId: 21, status: 'SELF_REVIEW' },
  ],
} as const;

const MODULES = ['PAYROLL', 'EMPLOYEE', 'LEAVE', 'ADMIN', 'REPORTS'] as const;
const ACTIONS = ['VIEW', 'EDIT', 'APPROVE', 'CREATE'] as const;

/** P1 performance authorities mirror contracts/p1-performance/openapi.yaml. */
export const ROLE_AUTHORITIES: Record<SeedAccount['role'], Authority[]> = {
  STAFF: ['EMPLOYEE:VIEW', 'LEAVE:VIEW', 'LEAVE:CREATE'],
  MANAGER: ['PAYROLL:VIEW', 'EMPLOYEE:VIEW', 'LEAVE:VIEW', 'ADMIN:VIEW', 'REPORTS:VIEW', 'LEAVE:CREATE', 'PERFORMANCE:VIEW'],
  EXECUTIVE: [
    ...MODULES.flatMap((module) => ACTIONS.map((action) => `${module}:${action}` as Authority)),
    'PERFORMANCE:VIEW',
    'PERFORMANCE:EDIT',
    'PERFORMANCE:APPROVE',
    'PERFORMANCE:CREATE',
    'PERFORMANCE:ADMIN',
    // P5 batch triggers / P2 x-deferred routes (contracts/p5-reporting-decommission/README.md).
    'LEAVE:ADMIN',
    'LEAVE:VIEW_ALL',
  ],
};
