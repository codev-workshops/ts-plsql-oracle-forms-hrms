import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { tokenStore } from '../../api/client';
import type { PayrollRun } from './api';
import { PayrollPage } from './index';

const auth = vi.hoisted(() => ({ user: { empId: 22, gradeLevel: 10 } }));
vi.mock('../../auth/AuthContext', () => ({ useAuth: () => auth }));

const period = {
  periodId: 7, periodName: '2026-11 (Nov)', frequency: 'MONTHLY', startDate: '2026-11-01',
  endDate: '2026-11-30', payDate: '2026-11-30', status: 'OPEN',
};
const run: PayrollRun = {
  runId: 42, periodId: 7, runType: 'REGULAR', status: 'PENDING', employeeCount: 0,
  totalGross: 0, totalDeductions: 0, totalNet: 0,
};
const line = {
  detailId: 1, empId: 22, elementId: 1, elementCode: 'BASE_PAY', elementType: 'EARNING',
  amount: 7916.67, status: 'CALCULATED',
};
const slip = {
  runId: 42, empId: 22, employeeName: 'THOMAS BAKER', period, lines: [line],
  gross: 7916.67, deductions: 1200, taxes: 1000, net: 6716.67,
  ytdGross: 15833.34, ytdTaxes: 2000, ytdNet: 13433.34,
};

function showPage(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes><Route path="/payroll/*" element={<PayrollPage />} /></Routes>
    </MemoryRouter>,
  );
}

function mockFetch(handler: (url: string, init?: RequestInit) => Response) {
  const fetcher = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => handler(String(input), init));
  vi.stubGlobal('fetch', fetcher);
  return fetcher;
}

function ok(data: unknown) {
  return Response.json(data);
}

beforeEach(() => {
  tokenStore.set('test-token');
  auth.user.gradeLevel = 10;
});

afterEach(() => {
  tokenStore.clear();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

it('creates a run from a pay period, calculates it and opens an employee payslip', async () => {
  let calculated = false;
  const fetcher = mockFetch((url, init) => {
    if (url.includes('/periods?')) return ok([period]);
    if (url.endsWith('/runs')) return ok(init?.method === 'POST' ? run : []);
    if (url.endsWith('/runs/42')) return ok(run);
    if (url.endsWith('/runs/42/details')) return ok(calculated ? [line] : []);
    if (url.endsWith('/runs/42/calculate')) {
      calculated = true;
      return ok({ ...run, status: 'CALCULATED', employeeCount: 1, totalGross: 7916.67, totalDeductions: 1200, totalNet: 6716.67 });
    }
    if (url.endsWith('/payslips/42/22')) return ok(slip);
    return new Response(null, { status: 404 });
  });
  const user = userEvent.setup();
  showPage('/payroll');
  await screen.findByRole('option', { name: period.periodName });
  await user.selectOptions(screen.getByLabelText('Run period'), '7');
  await user.click(screen.getByRole('button', { name: 'Create run' }));
  expect(await screen.findByRole('heading', { name: 'Payroll run #42' })).toBeVisible();
  expect(fetcher).toHaveBeenCalledWith('/api/v1/payroll/runs', expect.objectContaining({
    method: 'POST', body: JSON.stringify({ periodId: 7, runType: 'REGULAR' }),
  }));

  await user.click(await screen.findByRole('button', { name: 'Calculate payroll' }));
  expect(await screen.findByRole('button', { name: 'Approve payroll' })).toBeVisible();
  expect(screen.queryByRole('button', { name: 'Calculate payroll' })).not.toBeInTheDocument();
  await user.selectOptions(screen.getByLabelText('Filter by employee'), '22');
  expect(within(screen.getByRole('table', { name: 'Pay elements' })).getByText('BASE_PAY')).toBeVisible();
  await user.click(screen.getByRole('link', { name: 'View employee payslip' }));
  expect(await screen.findByRole('heading', { name: 'THOMAS BAKER' })).toBeVisible();
  expect(screen.getByText('$15,833.34')).toBeVisible();
});

it('shows run information to a payroll viewer without mutation controls', async () => {
  auth.user.gradeLevel = 6;
  mockFetch((url) => ok(url.endsWith('/details') ? [line] : { ...run, status: 'CALCULATED' }));
  showPage('/payroll/runs/42');
  expect(await screen.findByRole('heading', { name: 'Payroll run #42' })).toBeVisible();
  expect(screen.getByRole('button', { name: 'Download pay register' })).toBeVisible();
  expect(screen.queryByRole('button', { name: 'Approve payroll' })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Reverse run' })).not.toBeInTheDocument();
});

it('uses the signed-in employee for a self-service lookup', async () => {
  auth.user.gradeLevel = 4;
  const fetcher = mockFetch(() => ok(slip));
  const user = userEvent.setup();
  showPage('/payroll/payslips?empId=1');
  expect(screen.getByRole('heading', { name: 'My payslip' })).toBeVisible();
  expect(screen.queryByLabelText('Employee ID')).not.toBeInTheDocument();
  await user.type(screen.getByLabelText('Run ID'), '42');
  await user.click(screen.getByRole('button', { name: 'Find payslip' }));
  expect(await screen.findByRole('heading', { name: 'THOMAS BAKER' })).toBeVisible();
  expect(fetcher).toHaveBeenCalledWith('/api/v1/payroll/payslips/42/22', expect.anything());
});

it('shows the API error when a payslip cannot be found', async () => {
  mockFetch(() => Response.json({ code: '-20990', message: 'Payslip not found' }, { status: 404 }));
  showPage('/payroll/payslips?runId=42&empId=22');
  expect(await screen.findByRole('alert')).toHaveTextContent('Payslip not found (-20990)');
});
