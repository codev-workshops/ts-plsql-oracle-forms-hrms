import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { api } from '../../api/client';
import type { EmployeeListQuery, EmploymentStatus } from '../../api/types';
import { formatDate } from '../../app/format';
import { ReferenceDropdown } from '../../components/ReferenceDropdown';
import { useEmployeeModule } from './EmployeeModuleContext';
import { enumValues, parseForm, type FormValues } from './formUtils';
import { employeeKeys } from './queryKeys';
import { StatusBadge } from './StatusBadge';

const PAGE_SIZE = 20;
const STATUSES = enumValues('EmployeeListQuery', 'status') as EmploymentStatus[];
const EMPTY: FormValues = { q: '', deptId: '', status: 'ACTIVE', hireDateFrom: '', hireDateTo: '' };

/**
 * `EMP_SEARCH` block + `EMPLOYEE` grid of HRMS_EMPLOYEE.fmb (COMPONENT_MAPPING.md §3) →
 * `GET /api/employees` with the `EmployeeListQuery` filters (validation-schema.json).
 */
export function EmployeeGrid() {
  const navigate = useNavigate();
  const { writable, canEdit } = useEmployeeModule();
  const [form, setForm] = useState<FormValues>(EMPTY);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [query, setQuery] = useState<EmployeeListQuery>({ status: 'ACTIVE', page: 0, size: PAGE_SIZE });
  const set = (key: string, value: string) => setForm((old) => ({ ...old, [key]: value }));

  const list = useQuery({
    queryKey: employeeKeys.list(query),
    queryFn: () => api.employees.listEmployees(query),
    placeholderData: keepPreviousData,
  });

  const search = (event: FormEvent) => {
    event.preventDefault();
    const parsed = parseForm<EmployeeListQuery>('EmployeeListQuery', form);
    if (!parsed.ok) {
      setErrors(parsed.errors);
      return;
    }
    setErrors({});
    setQuery({ ...parsed.data, page: 0, size: PAGE_SIZE });
  };

  const reset = () => {
    setForm(EMPTY);
    setErrors({});
    setQuery({ status: 'ACTIVE', page: 0, size: PAGE_SIZE });
  };

  const page = list.data;
  return (
    <section aria-labelledby="employee-grid-title">
      <div className="toolbar">
        <h2 id="employee-grid-title">Employees</h2>
        {writable && canEdit && (
          <Link to="/employees/new" className="button">
            New employee
          </Link>
        )}
      </div>
      <form onSubmit={search} noValidate className="search-form" aria-label="Employee search">
        <div className="field">
          <label htmlFor="emp-q">Search</label>
          <input id="emp-q" type="search" value={form.q} onChange={(e) => set('q', e.target.value)} placeholder="Name, number or e-mail" maxLength={100} />
          {errors.q && <span role="alert" className="field-error">{errors.q}</span>}
        </div>
        <ReferenceDropdown source="departments" label="Department" value={form.deptId || null} onChange={(v) => set('deptId', v ?? '')} placeholder="All departments" />
        <div className="field">
          <label htmlFor="emp-status">Status</label>
          <select id="emp-status" value={form.status} onChange={(e) => set('status', e.target.value)}>
            <option value="">All statuses</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {s.replace(/_/g, ' ')}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="emp-hired-from">Hired from</label>
          <input id="emp-hired-from" type="date" value={form.hireDateFrom} onChange={(e) => set('hireDateFrom', e.target.value)} aria-invalid={errors.hireDateFrom ? true : undefined} />
          {errors.hireDateFrom && <span role="alert" className="field-error">{errors.hireDateFrom}</span>}
        </div>
        <div className="field">
          <label htmlFor="emp-hired-to">Hired to</label>
          <input id="emp-hired-to" type="date" value={form.hireDateTo} onChange={(e) => set('hireDateTo', e.target.value)} aria-invalid={errors.hireDateTo ? true : undefined} />
          {errors.hireDateTo && <span role="alert" className="field-error">{errors.hireDateTo}</span>}
        </div>
        <div className="actions">
          <button type="submit">Search</button>
          <button type="button" onClick={reset}>Clear</button>
        </div>
      </form>

      {list.isPending ? (
        <p role="status">Loading…</p>
      ) : list.isError ? (
        <p role="alert">Could not load employees.</p>
      ) : page && page.content.length ? (
        <>
          <table className="grid" aria-label="Employees" aria-busy={list.isFetching}>
            <thead>
              <tr>
                <th>Number</th>
                <th>Name</th>
                <th>Department</th>
                <th>Job title</th>
                <th>Manager</th>
                <th>Hired</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {page.content.map((e) => (
                <tr key={e.id} onClick={() => navigate(`/employees/${e.id}`)} className="clickable">
                  <td>
                    <Link to={`/employees/${e.id}`}>{e.empNumber}</Link>
                  </td>
                  <td>
                    {e.lastName}, {e.firstName}
                  </td>
                  <td>{e.deptName}</td>
                  <td>{e.jobTitle}</td>
                  <td>{e.managerName ?? '—'}</td>
                  <td>{formatDate(e.hireDate)}</td>
                  <td>
                    <StatusBadge status={e.employmentStatus} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="toolbar toolbar-pagination" aria-label="Pagination">
            <button type="button" disabled={page.page === 0} onClick={() => setQuery((q) => ({ ...q, page: (q.page ?? 0) - 1 }))}>
              Previous
            </button>
            <span data-testid="page-info">
              Page {page.page + 1} of {Math.max(page.totalPages, 1)} ({page.totalElements} employees)
            </span>
            <button type="button" disabled={page.page + 1 >= page.totalPages} onClick={() => setQuery((q) => ({ ...q, page: (q.page ?? 0) + 1 }))}>
              Next
            </button>
          </div>
        </>
      ) : (
        <p>No employees match the search.</p>
      )}
    </section>
  );
}
