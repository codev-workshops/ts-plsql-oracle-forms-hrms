import { useEffect, useState, type FormEvent } from 'react';
import { Link, Route, Routes, useNavigate } from 'react-router-dom';
import { useAuth } from '../../auth/AuthContext';
import { employeeApi, type EmployeeCreate, type EmployeeSummary, type HeadcountRow, type SearchFilter } from './api';
import { EmployeeDetailView } from './EmployeeDetailView';
import { errorText, optionalNumber, RefSelect, useRefData } from './shared';

const HR_GRADE = 8;

export function EmployeePage() {
  return (
    <Routes>
      <Route index element={<EmployeeSearch />} />
      <Route path="new" element={<NewEmployee />} />
      <Route path=":empId" element={<EmployeeDetailView />} />
    </Routes>
  );
}

function EmployeeSearch() {
  const { user } = useAuth();
  const ref = useRefData();
  const [filter, setFilter] = useState<SearchFilter>({ status: 'ACTIVE' });
  const [rows, setRows] = useState<EmployeeSummary[] | null>(null);
  const [headcount, setHeadcount] = useState<HeadcountRow[]>([]);
  const [error, setError] = useState<string | null>(null);

  const run = (f: SearchFilter) => {
    setError(null);
    employeeApi.search(f).then(setRows).catch((e) => setError(errorText(e)));
  };
  useEffect(() => {
    run({ status: 'ACTIVE' });
    employeeApi.headcount().then(setHeadcount).catch(() => setHeadcount([]));
  }, []);

  const set = (k: keyof SearchFilter) => (v: string) => setFilter((f) => ({ ...f, [k]: v }));
  const submit = (e: FormEvent) => {
    e.preventDefault();
    run(filter);
  };

  return (
    <section>
      <h1>Employees</h1>
      {user && user.gradeLevel >= HR_GRADE && <p><Link to="new">New hire</Link></p>}
      <form className="card" onSubmit={submit} aria-label="Employee search">
        <div className="row">
          <label>Last name<input value={filter.lastName ?? ''} onChange={(e) => set('lastName')(e.target.value)} /></label>
          <label>First name<input value={filter.firstName ?? ''} onChange={(e) => set('firstName')(e.target.value)} /></label>
          <RefSelect label="Department" items={ref.departments} value={filter.deptId ?? ''} onChange={set('deptId')}
            emptyLabel="All" />
          <RefSelect label="Location" items={ref.locations} value={filter.locationId ?? ''} onChange={set('locationId')}
            emptyLabel="All" />
          <label>Status
            <select value={filter.status ?? ''} onChange={(e) => set('status')(e.target.value)}>
              <option value="">All</option>
              <option value="ACTIVE">Active</option>
              <option value="ON_LEAVE">On leave</option>
              <option value="SUSPENDED">Suspended</option>
              <option value="TERMINATED">Terminated</option>
            </select>
          </label>
          <label>Hired from<input type="date" value={filter.hiredFrom ?? ''} onChange={(e) => set('hiredFrom')(e.target.value)} /></label>
          <label>Hired to<input type="date" value={filter.hiredTo ?? ''} onChange={(e) => set('hiredTo')(e.target.value)} /></label>
        </div>
        <button type="submit">Search</button>
      </form>
      {error && <p className="error" role="alert">{error}</p>}
      {rows && (
        <table aria-label="Employees">
          <thead>
            <tr><th>Number</th><th>Name</th><th>Department</th><th>Job</th><th>Location</th><th>Hired</th><th>Status</th></tr>
          </thead>
          <tbody>
            {rows.length === 0 && <tr><td colSpan={7}>No employees found</td></tr>}
            {rows.map((r) => (
              <tr key={r.empId}>
                <td>{r.empNumber}</td>
                <td><Link to={String(r.empId)}>{r.fullName}</Link></td>
                <td>{r.deptName}</td>
                <td>{r.jobTitle}</td>
                <td>{r.locationCode}</td>
                <td>{r.hireDate}</td>
                <td>{r.employmentStatus}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {headcount.length > 0 && (
        <table aria-label="Headcount">
          <thead><tr><th>Department</th><th>Headcount</th></tr></thead>
          <tbody>
            {headcount.map((h) => <tr key={h.deptId}><td>{h.deptName}</td><td>{h.headcount}</td></tr>)}
          </tbody>
        </table>
      )}
    </section>
  );
}

function NewEmployee() {
  const ref = useRefData();
  const navigate = useNavigate();
  const [form, setForm] = useState({ firstName: '', lastName: '', email: '', hireDate: '', deptId: '', jobId: '',
    managerEmpId: '', locationId: '', employmentType: 'FULL_TIME', baseSalary: '', phoneWork: '' });
  const [error, setError] = useState<string | null>(null);
  const set = (k: keyof typeof form) => (v: string) => setForm((f) => ({ ...f, [k]: v }));

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    const body: EmployeeCreate = {
      firstName: form.firstName, lastName: form.lastName, email: form.email, hireDate: form.hireDate,
      deptId: Number(form.deptId), jobId: Number(form.jobId), managerEmpId: optionalNumber(form.managerEmpId),
      locationId: optionalNumber(form.locationId), employmentType: form.employmentType,
      baseSalary: optionalNumber(form.baseSalary), phoneWork: form.phoneWork || undefined,
    };
    try {
      const created = await employeeApi.create(body);
      navigate(`../${created.empId}`, { state: { warnings: created.warnings ?? [] } });
    } catch (err) {
      setError(errorText(err));
    }
  };

  return (
    <section>
      <h1>New hire</h1>
      <form className="card" onSubmit={submit} aria-label="New hire">
        <div className="row">
          <label>First name<input value={form.firstName} onChange={(e) => set('firstName')(e.target.value)} required /></label>
          <label>Last name<input value={form.lastName} onChange={(e) => set('lastName')(e.target.value)} required /></label>
          <label>Email<input type="email" value={form.email} onChange={(e) => set('email')(e.target.value)} required /></label>
          <label>Work phone<input value={form.phoneWork} onChange={(e) => set('phoneWork')(e.target.value)} /></label>
          <label>Hire date<input type="date" value={form.hireDate} onChange={(e) => set('hireDate')(e.target.value)} required /></label>
        </div>
        <div className="row">
          <RefSelect label="Department" items={ref.departments} value={form.deptId} onChange={set('deptId')} required />
          <RefSelect label="Job" items={ref.jobs} value={form.jobId} onChange={set('jobId')} required />
          <RefSelect label="Location" items={ref.locations} value={form.locationId} onChange={set('locationId')}
            emptyLabel="Department default" />
          <label>Manager employee ID<input inputMode="numeric" value={form.managerEmpId} onChange={(e) => set('managerEmpId')(e.target.value)} /></label>
          <label>Employment type
            <select value={form.employmentType} onChange={(e) => set('employmentType')(e.target.value)}>
              <option value="FULL_TIME">Full time</option>
              <option value="PART_TIME">Part time</option>
              <option value="CONTRACT">Contract</option>
              <option value="INTERN">Intern</option>
            </select>
          </label>
          <label>Base salary<input type="number" min="0" value={form.baseSalary} onChange={(e) => set('baseSalary')(e.target.value)} /></label>
        </div>
        <button type="submit">Create employee</button>
      </form>
      {error && <p className="error" role="alert">{error}</p>}
      <p><Link to="..">Back to search</Link></p>
    </section>
  );
}
