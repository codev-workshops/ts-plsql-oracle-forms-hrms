import { useQuery } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { api } from '../../api/client';
import type { EmployeeListQuery, EmploymentStatus } from '../../api/types';
import { formatDate } from '../../app/format';
import { ReferenceDropdown } from '../../components/ReferenceDropdown';
import { EmployeeStatusBadge } from './EmployeeStatusBadge';
import { compact, employeeListKey, useEmployeeWrite } from './employeeShared';

const STATUSES: EmploymentStatus[] = ['ACTIVE', 'ON_LEAVE', 'SUSPENDED', 'TERMINATED'];
const PAGE_SIZE = 20;

interface Filters {
  q: string;
  lastName: string;
  deptId: string | null;
  jobId: string | null;
  status: EmploymentStatus | '';
  includeInactive: boolean;
}

const EMPTY: Filters = { q: '', lastName: '', deptId: null, jobId: null, status: '', includeInactive: false };

function toQuery(f: Filters, page: number): EmployeeListQuery {
  return {
    ...compact({
      q: f.q.trim(),
      lastName: f.lastName.trim(),
      deptId: f.deptId ? Number(f.deptId) : undefined,
      jobId: f.jobId ? Number(f.jobId) : undefined,
      status: f.status || undefined,
    }),
    ...(f.includeInactive || f.status ? {} : { active: true }),
    page,
    size: PAGE_SIZE,
  };
}

/** `EMPLOYEE` block in query mode + `BTN_SEARCH` (HRMS_EMPLOYEE.xml) → `GET /api/employees`. */
export function EmployeeGrid() {
  const navigate = useNavigate();
  const { canEditEmployee } = useEmployeeWrite();
  const [draft, setDraft] = useState<Filters>(EMPTY);
  const [applied, setApplied] = useState<Filters>(EMPTY);
  const [page, setPage] = useState(0);
  const params = toQuery(applied, page);
  const result = useQuery({ queryKey: employeeListKey(params), queryFn: () => api.employees.listEmployees(params), placeholderData: (prev) => prev });

  const search = (event: FormEvent) => {
    event.preventDefault();
    setPage(0);
    setApplied(draft);
  };
  const reset = () => {
    setDraft(EMPTY);
    setApplied(EMPTY);
    setPage(0);
  };

  return (
    <section aria-labelledby="employee-grid-title">
      <h2 id="employee-grid-title">Search</h2>
      <form className="filters" onSubmit={search} role="search" aria-label="Employee search">
        <div className="field">
          <label htmlFor="empSearchQ">Name or number</label>
          <input id="empSearchQ" type="search" value={draft.q} maxLength={100} onChange={(e) => setDraft({ ...draft, q: e.target.value })} />
        </div>
        <div className="field">
          <label htmlFor="empSearchLast">Last name starts with</label>
          <input id="empSearchLast" value={draft.lastName} maxLength={50} onChange={(e) => setDraft({ ...draft, lastName: e.target.value })} />
        </div>
        <ReferenceDropdown source="departments" label="Department" value={draft.deptId} onChange={(v) => setDraft({ ...draft, deptId: v })} placeholder="All" />
        <ReferenceDropdown source="job-titles" label="Job title" value={draft.jobId} onChange={(v) => setDraft({ ...draft, jobId: v })} placeholder="All" />
        <div className="field">
          <label htmlFor="empSearchStatus">Status</label>
          <select id="empSearchStatus" value={draft.status} onChange={(e) => setDraft({ ...draft, status: e.target.value as EmploymentStatus | '' })}>
            <option value="">Any</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>{s}</option>
            ))}
          </select>
        </div>
        <fieldset>
          <label>
            <input type="checkbox" checked={draft.includeInactive} onChange={(e) => setDraft({ ...draft, includeInactive: e.target.checked })} />
            Include inactive
          </label>
        </fieldset>
        <div className="actions">
          <button type="submit">Search</button>
          <button type="button" onClick={reset}>Clear</button>
          {canEditEmployee && (
            <button type="button" onClick={() => navigate('/employees/new')}>New employee</button>
          )}
        </div>
      </form>

      {result.isPending ? (
        <p role="status">Loading…</p>
      ) : result.isError ? (
        <p role="alert">Could not load employees.</p>
      ) : result.data.content.length ? (
        <>
          <table className="grid" aria-label="Employees">
            <thead>
              <tr><th>Number</th><th>Name</th><th>Department</th><th>Job title</th><th>Manager</th><th>Location</th><th>Hired</th><th>Status</th></tr>
            </thead>
            <tbody>
              {result.data.content.map((e) => (
                <tr key={e.id}>
                  <td><Link to={`/employees/${e.id}`}>{e.empNumber}</Link></td>
                  <td><Link to={`/employees/${e.id}`}>{e.lastName}, {e.firstName}</Link></td>
                  <td>{e.deptName}</td>
                  <td>{e.jobTitle}</td>
                  <td>{e.managerName ?? '—'}</td>
                  <td>{e.locationCode ?? '—'}</td>
                  <td>{formatDate(e.hireDate)}</td>
                  <td><EmployeeStatusBadge status={e.employmentStatus} /></td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="toolbar" aria-label="Pagination">
            <span role="status">
              {result.data.totalElements} employee{result.data.totalElements === 1 ? '' : 's'} · page {result.data.page + 1} of {Math.max(result.data.totalPages, 1)}
            </span>
            <span className="toolbar-pagination">
              <button type="button" onClick={() => setPage((p) => Math.max(0, p - 1))} disabled={page === 0}>Previous</button>
              <button type="button" onClick={() => setPage((p) => p + 1)} disabled={result.data.page + 1 >= result.data.totalPages}>Next</button>
            </span>
          </div>
        </>
      ) : (
        <p>No employees match the search.</p>
      )}
    </section>
  );
}
