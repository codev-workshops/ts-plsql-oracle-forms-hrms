import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderSignedIn, user } from '../test/harness';

const lookups = {
  'GET /api/v1/lookups/departments': [{ deptId: 10, deptName: 'Engineering' }],
  'GET /api/v1/lookups/job-titles': [{ jobId: 5, jobTitle: 'Engineer' }],
  'GET /api/v1/lookups/locations': [{ locationCode: 'HQ', locationName: 'Head Office' }],
};

describe('employee module', () => {
  it('lists active employees by default and creates one', async () => {
    const emp = { empId: 7, empNumber: 'EMP-000007', firstName: 'Grace', lastName: 'Hopper', deptName: 'Engineering', jobTitle: 'Engineer', employmentStatus: 'ACTIVE', hireDate: '2020-01-06' };
    const calls = renderSignedIn('/employees', {
      ...lookups,
      'GET /api/v1/employees': { items: [emp], page: 0, size: 50, total: 1 },
      'POST /api/v1/employees': { employee: emp, warnings: [] },
    });
    expect(await screen.findByRole('link', { name: 'EMP-000007' })).toBeInTheDocument();
    expect(calls.find((c) => c.path.startsWith('/api/v1/employees?'))?.path).toContain('includeInactive=false');

    const form = await screen.findByRole('form', { name: 'New employee' });
    await userEvent.type(within(form).getByLabelText(/First name/), 'Alan');
    await userEvent.type(within(form).getByLabelText(/Last name/), 'Turing');
    await userEvent.type(within(form).getByLabelText('Email'), 'not-an-email');
    expect(within(form).getByText('Enter a valid email address')).toBeInTheDocument();
    expect(within(form).getByRole('button', { name: 'Create employee' })).toBeDisabled();
    await userEvent.clear(within(form).getByLabelText('Email'));
    await userEvent.type(within(form).getByLabelText('Email'), 'alan@hrms.example');
    await userEvent.type(within(form).getByLabelText(/Hire date/), '2026-11-23');
    await userEvent.selectOptions(within(form).getByLabelText(/Department/), '10');
    await userEvent.selectOptions(within(form).getByLabelText(/Job/), '5');
    await userEvent.click(within(form).getByRole('button', { name: 'Create employee' }));
    await within(form).findByText('Create employee: done');
    expect(calls.find((c) => c.method === 'POST')?.body).toEqual({
      firstName: 'Alan', lastName: 'Turing', email: 'alan@hrms.example', hireDate: '2026-11-23', deptId: 10, jobId: 5,
    });
  });
});

describe('leave module', () => {
  it('shows balances and submits a request; cancel only for PENDING/APPROVED', async () => {
    const calls = renderSignedIn('/leave', {
      'GET /api/v1/leave/types': [{ leaveTypeId: 1, leaveTypeName: 'Annual Leave' }],
      'GET /api/v1/leave/balances': [{ leaveTypeId: 1, leaveTypeName: 'Annual Leave', calendarYear: 2026, used: 2, pending: 1, available: 12 }],
      'GET /api/v1/leave/requests': [
        { requestId: 11, leaveTypeName: 'Annual Leave', startDate: '2026-12-01', endDate: '2026-12-01', totalDays: 1, status: 'PENDING' },
        { requestId: 12, leaveTypeName: 'Annual Leave', startDate: '2026-10-01', endDate: '2026-10-01', totalDays: 1, status: 'REJECTED' },
      ],
      'GET /api/v1/leave/approvals': [],
      'POST /api/v1/leave/requests': { requestId: 13, status: 'PENDING' },
    });
    const balances = await screen.findByRole('table', { name: 'Leave balances' });
    expect(within(balances).getByText('12')).toBeInTheDocument();
    const mine = await screen.findByRole('table', { name: 'My requests' });
    expect(within(mine).getAllByRole('button', { name: 'Cancel' })).toHaveLength(1);

    const form = screen.getByRole('form', { name: 'Submit leave request' });
    await userEvent.selectOptions(within(form).getByLabelText(/Leave type/), '1');
    await userEvent.type(within(form).getByLabelText(/Start date/), '2026-12-07');
    await userEvent.type(within(form).getByLabelText(/End date/), '2026-12-08');
    await userEvent.click(within(form).getByRole('button', { name: 'Submit request' }));
    await within(form).findByText('Submit request: done');
    expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ leaveTypeId: 1, startDate: '2026-12-07', endDate: '2026-12-08' });
  });
});

describe('performance module', () => {
  it('lets an admin open a DRAFT cycle; offers no delete actions', async () => {
    const calls = renderSignedIn('/performance', {
      'GET /api/v1/performance/cycles': [{ cycleId: 3, cycleName: 'FY2026', cycleYear: 2026, status: 'DRAFT' }],
      'GET /api/v1/performance/reviews': [],
      'POST /api/v1/performance/cycles/3/open': { cycleId: 3, status: 'OPEN' },
    });
    await userEvent.click(await screen.findByRole('button', { name: 'Open' }));
    await waitFor(() => expect(calls.some((c) => c.path === '/api/v1/performance/cycles/3/open')).toBe(true));
    expect(screen.queryByRole('button', { name: /delete/i })).toBeNull();
  });
});

describe('payroll module', () => {
  it('calculates a PENDING run and approves a CALCULATED one', async () => {
    const calls = renderSignedIn('/payroll', {
      'GET /api/v1/payroll/pay-periods': [{ periodId: 1, periodName: 'NOV-2026', status: 'OPEN' }],
      'GET /api/v1/payroll/runs': [
        { runId: 20, periodName: 'NOV-2026', status: 'PENDING' },
        { runId: 21, periodName: 'OCT-2026', status: 'CALCULATED' },
      ],
      'POST /api/v1/payroll/runs/20/calculate': { runId: 20, status: 'CALCULATED' },
      'GET /api/v1/payroll/runs/20/payslips': [{ empNumber: 'EMP-000001', employeeName: 'Ada King', grossPay: 5000, totalDeductions: 1000, netPay: 4000 }],
      'GET /api/v1/payroll/runs/20/errors': [],
    });
    const runs = await screen.findByRole('table', { name: 'Payroll runs' });
    expect(within(runs).getAllByRole('button', { name: 'Calculate' })).toHaveLength(1);
    expect(within(runs).getAllByRole('button', { name: 'Approve' })).toHaveLength(1);
    await userEvent.click(within(runs).getByRole('button', { name: 'Calculate' }));
    expect(await screen.findByRole('table', { name: 'Payslips' })).toHaveTextContent('4000');
    expect(calls.some((c) => c.method === 'POST' && c.path === '/api/v1/payroll/runs/20/calculate')).toBe(true);
  });

  it('hides edit/approve actions without PAYROLL:EDIT/APPROVE', async () => {
    renderSignedIn(
      '/payroll',
      {
        'GET /api/v1/payroll/pay-periods': [],
        'GET /api/v1/payroll/runs': [{ runId: 20, periodName: 'NOV-2026', status: 'PENDING' }],
      },
      undefined,
      { ...user, permissions: { PAYROLL: ['VIEW'] } },
    );
    const runs = await screen.findByRole('table', { name: 'Payroll runs' });
    expect(within(runs).queryByRole('button', { name: 'Calculate' })).toBeNull();
    expect(screen.queryByRole('form', { name: 'New payroll run' })).toBeNull();
  });
});

describe('reports module', () => {
  it('labels pending, net available and legacy available in the leave summary', async () => {
    renderSignedIn('/reports', {
      'GET /api/v1/reports': [
        { id: 'LEAVE_SUMMARY', title: 'Leave summary', path: '/api/v1/reports/leave-summary', sourceView: 'VW_LEAVE_SUMMARY', permission: 'REPORTS:VIEW', available: true },
        { id: 'EMPLOYEE_COMPENSATION', title: 'Employee compensation', path: '/api/v1/reports/employee-compensation', sourceView: 'VW_EMPLOYEE_COMPENSATION', permission: 'REPORTS:VIEW+PAYROLL:VIEW', available: false },
      ],
      'GET /api/v1/reports/leave-summary': [
        { empId: 1, empNumber: 'EMP-000001', empName: 'Ada King', deptName: 'Exec', leaveTypeName: 'Annual Leave', calendarYear: 2026, used: 2, pending: 3, available: 10, availableBeforePending: 13, utilizationPct: 10 },
      ],
    });
    expect(await screen.findByRole('button', { name: 'Employee compensation' })).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: 'Leave summary' }));
    const table = await screen.findByRole('table', { name: 'LEAVE_SUMMARY' });
    expect(within(table).getByText('Pending')).toBeInTheDocument();
    expect(within(table).getByText('Available (net of pending)')).toBeInTheDocument();
    expect(within(table).getByText('Available before pending (legacy)')).toBeInTheDocument();
    expect(within(table).getByText('13')).toBeInTheDocument();
  });
});
