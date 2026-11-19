import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../../auth/AuthContext';
import { EmployeePage } from './index';

type Handler = (url: string, init?: RequestInit) => { status: number; body?: unknown } | undefined;

const hrUser = { empId: 10, username: 'patricia.williams@company.com', fullName: 'PATRICIA WILLIAMS', gradeLevel: 9,
  sessionId: 1 };
const staffUser = { empId: 22, username: 'thomas.baker@company.com', fullName: 'THOMAS BAKER', gradeLevel: 4,
  sessionId: 2 };
const thomas = {
  empId: 22, empNumber: 'EMP-000022', firstName: 'THOMAS', lastName: 'BAKER', fullName: 'THOMAS BAKER',
  email: 'thomas.baker@company.com', deptId: 20, deptName: 'Finance', jobId: 42, jobTitle: 'Senior Accountant',
  gradeLevel: 4, managerEmpId: 21, locationCode: 'HQ', hireDate: '2018-09-10', employmentStatus: 'ACTIVE',
  active: true, tenureYears: 8.2, employmentType: 'FULL_TIME',
};
const refs: Record<string, unknown> = {
  '/departments': [{ id: 20, code: 'FIN', name: 'Finance' }],
  '/job-titles': [{ id: 42, code: 'SR-ACCT', name: 'Senior Accountant', gradeId: 4 }],
  '/locations': [{ id: 2, code: 'HQ', name: 'Corporate Headquarters' }],
  '/departments/headcount': [{ deptId: 20, deptName: 'Finance', headcount: 5 }],
};

function mockFetch(user: object, handler: Handler) {
  const fn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const path = url.replace('/api/v1', '').split('?')[0];
    const result = url.endsWith('/auth/me') ? { status: 200, body: user }
      : handler(url, init) ?? (path in refs ? { status: 200, body: refs[path] } : { status: 404,
        body: { code: '-20990', message: 'nf' } });
    return new Response(result.body === undefined ? null : JSON.stringify(result.body), { status: result.status });
  });
  vi.stubGlobal('fetch', fn);
  return fn;
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <Routes><Route path="/employees/*" element={<EmployeePage />} /></Routes>
      </AuthProvider>
    </MemoryRouter>,
  );
}

describe('EmployeePage', () => {
  beforeEach(() => window.localStorage.setItem('hrms.token', 't'));
  afterEach(() => vi.unstubAllGlobals());

  it('searches with filters as query parameters', async () => {
    const fetchMock = mockFetch(staffUser, (url) => (url.includes('/employees?') ? { status: 200, body: [thomas] }
      : undefined));
    renderAt('/employees');
    expect(await screen.findByRole('link', { name: 'THOMAS BAKER' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'New hire' })).not.toBeInTheDocument();
    expect(await screen.findByRole('cell', { name: '5' })).toBeInTheDocument();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('Last name'), 'bak');
    await user.click(screen.getByRole('button', { name: 'Search' }));
    await waitFor(() => expect(fetchMock.mock.calls.map((c) => String(c[0])))
      .toContain('/api/v1/employees?status=ACTIVE&lastName=bak'));
  });

  it('creates a new hire and shows the salary warning on the detail page', async () => {
    let posted: unknown;
    mockFetch(hrUser, (url, init) => {
      if (url.endsWith('/employees') && init?.method === 'POST') {
        posted = JSON.parse(String(init.body));
        return { status: 201, body: { ...thomas, empId: 1001, fullName: 'ADA LOVELACE',
          warnings: ['Salary 10000 is below minimum for grade G4'] } };
      }
      if (url.endsWith('/employees/1001')) return { status: 200, body: { ...thomas, empId: 1001, fullName: 'ADA LOVELACE' } };
      return undefined;
    });
    renderAt('/employees/new');
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('First name'), 'Ada');
    await user.type(screen.getByLabelText('Last name'), 'Lovelace');
    await user.type(screen.getByLabelText('Email'), 'ada@company.com');
    await user.type(screen.getByLabelText('Hire date'), '2026-12-01');
    await screen.findByRole('option', { name: 'Finance' });
    await user.selectOptions(screen.getByLabelText('Department'), '20');
    await user.selectOptions(screen.getByLabelText('Job'), '42');
    await user.type(screen.getByLabelText('Base salary'), '10000');
    await user.click(screen.getByRole('button', { name: 'Create employee' }));
    expect(await screen.findByRole('heading', { name: 'ADA LOVELACE' })).toBeInTheDocument();
    expect(screen.getByText(/below minimum for grade G4/)).toBeInTheDocument();
    expect(posted).toMatchObject({ firstName: 'Ada', deptId: 20, jobId: 42, baseSalary: 10000,
      employmentType: 'FULL_TIME' });
  });

  it('shows the legacy error code when creation is rejected', async () => {
    mockFetch(hrUser, (url, init) => (url.endsWith('/employees') && init?.method === 'POST'
      ? { status: 409, body: { code: '-20502', message: 'Email already in use' } } : undefined));
    renderAt('/employees/new');
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('First name'), 'A');
    await user.type(screen.getByLabelText('Last name'), 'B');
    await user.type(screen.getByLabelText('Email'), 'thomas.baker@company.com');
    await user.type(screen.getByLabelText('Hire date'), '2026-12-01');
    await screen.findByRole('option', { name: 'Finance' });
    await user.selectOptions(screen.getByLabelText('Department'), '20');
    await user.selectOptions(screen.getByLabelText('Job'), '42');
    await user.click(screen.getByRole('button', { name: 'Create employee' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Email already in use (-20502)');
  });

  it('lets an employee manage their own dependents but hides lifecycle actions', async () => {
    const dependents: unknown[] = [];
    mockFetch(staffUser, (url, init) => {
      if (url.endsWith('/employees/22')) return { status: 200, body: thomas };
      if (url.endsWith('/employees/22/dependents')) {
        if (init?.method === 'POST') {
          const d = { ...JSON.parse(String(init.body)), dependentId: 5 };
          dependents.push(d);
          return { status: 201, body: d };
        }
        return { status: 200, body: dependents };
      }
      return undefined;
    });
    renderAt('/employees/22');
    expect(await screen.findByRole('heading', { name: 'THOMAS BAKER' })).toBeInTheDocument();
    expect(screen.queryByRole('tab', { name: 'Lifecycle' })).not.toBeInTheDocument();
    const user = userEvent.setup();
    await user.click(screen.getByRole('tab', { name: 'Dependents' }));
    await user.type(screen.getByLabelText('First name'), 'Mia');
    await user.type(screen.getByLabelText('Last name'), 'Baker');
    await user.click(screen.getByRole('button', { name: 'Add dependent' }));
    expect(await screen.findByRole('cell', { name: 'Mia Baker' })).toBeInTheDocument();
  });

  it('runs a termination from the lifecycle tab for HR', async () => {
    let body: unknown;
    mockFetch(hrUser, (url, init) => {
      if (url.endsWith('/employees/22')) return { status: 200, body: thomas };
      if (url.endsWith('/employees/22/terminate')) {
        body = JSON.parse(String(init?.body));
        return { status: 200, body: { ...thomas, employmentStatus: 'TERMINATED', active: false } };
      }
      return undefined;
    });
    renderAt('/employees/22');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('tab', { name: 'Lifecycle' }));
    await user.type(screen.getByLabelText('Effective date'), '2026-11-30');
    await user.type(screen.getByLabelText('Reason'), 'VOLUNTARY');
    await user.click(screen.getByRole('button', { name: 'Terminate' }));
    expect(await screen.findByText('Termination complete')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Rehire' })).toBeInTheDocument();
    expect(body).toEqual({ terminationDate: '2026-11-30', reason: 'VOLUNTARY' });
  });
});
