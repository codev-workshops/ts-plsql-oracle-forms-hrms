import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import { useAuth } from '../../auth/AuthContext';
import {
  employeeApi, type Dependent, type EmergencyContact, type EmployeeDetail, type EmployeeHistory,
  type EmployeeSummary, type OrgChartNode, type SalaryRecord,
} from './api';
import { errorText, money, optionalNumber, RefSelect, useRefData } from './shared';

type Tab = 'details' | 'history' | 'salary' | 'dependents' | 'contacts' | 'team' | 'actions';
const HR_GRADE = 8;

export function EmployeeDetailView() {
  const empId = Number(useParams().empId);
  const { user } = useAuth();
  const location = useLocation();
  const [emp, setEmp] = useState<EmployeeDetail | null>(null);
  const [tab, setTab] = useState<Tab>('details');
  const [error, setError] = useState<string | null>(null);
  const [warnings, setWarnings] = useState<string[]>(
    (location.state as { warnings?: string[] } | null)?.warnings ?? []);

  const reload = useCallback(() => {
    employeeApi.get(empId).then(setEmp).catch((e) => setError(errorText(e)));
  }, [empId]);
  useEffect(reload, [reload]);

  const isHr = !!user && user.gradeLevel >= HR_GRADE;
  const canEditSelf = !!user && (user.empId === empId || isHr);
  const tabs: { id: Tab; label: string; show: boolean }[] = [
    { id: 'details', label: 'Details', show: true },
    { id: 'history', label: 'History', show: true },
    { id: 'salary', label: 'Salary', show: !!emp?.currentSalary },
    { id: 'dependents', label: 'Dependents', show: true },
    { id: 'contacts', label: 'Emergency contacts', show: true },
    { id: 'team', label: 'Reports & org chart', show: true },
    { id: 'actions', label: 'Lifecycle', show: isHr },
  ];

  if (error && !emp) return <p className="error" role="alert">{error}</p>;
  if (!emp) return <p>Loading…</p>;

  const changed = (updated: EmployeeDetail) => {
    setEmp(updated);
    setWarnings(updated.warnings ?? []);
  };

  return (
    <section>
      <p><Link to="..">Back to search</Link></p>
      <h1>{emp.fullName}</h1>
      <p>{emp.empNumber} · {emp.jobTitle} · {emp.deptName} · <strong>{emp.employmentStatus}</strong></p>
      {warnings.map((w) => <p key={w} className="error" role="status">Warning: {w}</p>)}
      <div className="tabs" role="tablist">
        {tabs.filter((t) => t.show).map((t) => (
          <button key={t.id} role="tab" aria-selected={tab === t.id} onClick={() => setTab(t.id)}>{t.label}</button>
        ))}
      </div>
      {tab === 'details' && <Details emp={emp} canEdit={canEditSelf} onSaved={changed} />}
      {tab === 'history' && <History empId={empId} />}
      {tab === 'salary' && <Salary empId={empId} />}
      {tab === 'dependents' && <Dependents empId={empId} canEdit={canEditSelf} />}
      {tab === 'contacts' && <Contacts empId={empId} canEdit={canEditSelf} />}
      {tab === 'team' && <Team empId={empId} />}
      {tab === 'actions' && <Lifecycle emp={emp} onDone={changed} />}
    </section>
  );
}

function Details({ emp, canEdit, onSaved }: { emp: EmployeeDetail; canEdit: boolean;
  onSaved: (e: EmployeeDetail) => void }) {
  const [form, setForm] = useState({ email: emp.email ?? '', phoneWork: emp.phoneWork ?? '',
    phoneMobile: emp.phoneMobile ?? '', addressLine1: emp.addressLine1 ?? '', city: emp.city ?? '',
    state: emp.state ?? '', postalCode: emp.postalCode ?? '' });
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    try {
      onSaved(await employeeApi.update(emp.empId, form));
      setMsg({ ok: true, text: 'Saved' });
    } catch (err) {
      setMsg({ ok: false, text: errorText(err) });
    }
  };
  const field = (k: keyof typeof form, label: string) => (
    <label>{label}<input value={form[k]} disabled={!canEdit}
      onChange={(e) => setForm((f) => ({ ...f, [k]: e.target.value }))} /></label>
  );
  return (
    <div className="card">
      <table aria-label="Employment">
        <tbody>
          <tr><th>Hire date</th><td>{emp.hireDate}</td><th>Tenure (years)</th><td>{emp.tenureYears}</td></tr>
          <tr><th>Grade</th><td>{emp.gradeLevel}</td><th>Location</th><td>{emp.locationCode}</td></tr>
          <tr><th>Manager</th><td>{emp.managerEmpId ? <Link to={`../${emp.managerEmpId}`}>{emp.managerEmpId}</Link> : '—'}</td>
            <th>Type</th><td>{emp.employmentType}</td></tr>
          {emp.terminationDate && <tr><th>Terminated</th><td>{emp.terminationDate}</td><th>Reason</th>
            <td>{emp.terminationReason}</td></tr>}
        </tbody>
      </table>
      <form onSubmit={submit} aria-label="Contact details">
        <div className="row">
          {field('email', 'Email')}{field('phoneWork', 'Work phone')}{field('phoneMobile', 'Mobile phone')}
        </div>
        <div className="row">
          {field('addressLine1', 'Address')}{field('city', 'City')}{field('state', 'State')}
          {field('postalCode', 'Postal code')}
        </div>
        {canEdit && <button type="submit">Save details</button>}
      </form>
      {msg && <p className={msg.ok ? 'success' : 'error'} role="status">{msg.text}</p>}
    </div>
  );
}

function useList<T>(load: () => Promise<T[]>) {
  const [rows, setRows] = useState<T[]>([]);
  const [error, setError] = useState<string | null>(null);
  const reload = useCallback(() => {
    load().then((r) => { setRows(r); setError(null); }).catch((e) => setError(errorText(e)));
  }, [load]);
  useEffect(reload, [reload]);
  return { rows, error, reload, setError };
}

function describe(values: Record<string, unknown>): string {
  return Object.entries(values).map(([k, v]) => `${k}: ${String(v)}`).join(', ');
}

function History({ empId }: { empId: number }) {
  const load = useCallback(() => employeeApi.history(empId), [empId]);
  const { rows, error } = useList<EmployeeHistory>(load);
  return (
    <>
      {error && <p className="error" role="alert">{error}</p>}
      <table aria-label="History">
        <thead><tr><th>Date</th><th>Change</th><th>From</th><th>To</th><th>Reason</th><th>By</th></tr></thead>
        <tbody>
          {rows.map((h) => (
            <tr key={h.historyId}><td>{h.effectiveDate}</td><td>{h.changeType}</td><td>{describe(h.oldValues)}</td>
              <td>{describe(h.newValues)}</td><td>{h.reason}</td><td>{h.createdBy}</td></tr>
          ))}
        </tbody>
      </table>
    </>
  );
}

function Salary({ empId }: { empId: number }) {
  const load = useCallback(() => employeeApi.salaryHistory(empId), [empId]);
  const { rows, error } = useList<SalaryRecord>(load);
  return (
    <>
      {error && <p className="error" role="alert">{error}</p>}
      <table aria-label="Salary history">
        <thead><tr><th>Effective</th><th>End</th><th>Base salary</th><th>Change %</th><th>Reason</th></tr></thead>
        <tbody>
          {rows.map((s) => (
            <tr key={s.salaryId}><td>{s.effectiveDate}</td><td>{s.endDate ?? '—'}</td><td>{money(s.baseSalary)}</td>
              <td>{s.changePct ?? '—'}</td><td>{s.changeReason}</td></tr>
          ))}
        </tbody>
      </table>
    </>
  );
}

const RELATIONSHIPS = ['SPOUSE', 'CHILD', 'PARENT', 'DOMESTIC_PARTNER', 'OTHER'];

function Dependents({ empId, canEdit }: { empId: number; canEdit: boolean }) {
  const load = useCallback(() => employeeApi.dependents(empId), [empId]);
  const { rows, error, reload, setError } = useList<Dependent>(load);
  const empty: Dependent = { firstName: '', lastName: '', relationship: 'CHILD', dateOfBirth: '', benefitsEligible: false };
  const [form, setForm] = useState<Dependent>(empty);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    try {
      const body = { ...form, dateOfBirth: form.dateOfBirth || undefined };
      if (form.dependentId) await employeeApi.updateDependent(empId, form.dependentId, body);
      else await employeeApi.addDependent(empId, body);
      setForm(empty);
      reload();
    } catch (err) {
      setError(errorText(err));
    }
  };
  const remove = async (id: number) => {
    try {
      await employeeApi.deleteDependent(empId, id);
      reload();
    } catch (err) {
      setError(errorText(err));
    }
  };
  return (
    <>
      {error && <p className="error" role="alert">{error}</p>}
      <table aria-label="Dependents">
        <thead><tr><th>Name</th><th>Relationship</th><th>Date of birth</th><th>Benefits</th><th /></tr></thead>
        <tbody>
          {rows.map((d) => (
            <tr key={d.dependentId}><td>{d.firstName} {d.lastName}</td><td>{d.relationship}</td>
              <td>{d.dateOfBirth ?? '—'}</td><td>{d.benefitsEligible ? 'Yes' : 'No'}</td>
              <td>{canEdit && <>
                <button onClick={() => setForm({ ...d, dateOfBirth: d.dateOfBirth ?? '' })}>Edit</button>{' '}
                <button onClick={() => remove(d.dependentId!)}>Remove</button></>}</td></tr>
          ))}
        </tbody>
      </table>
      {canEdit && (
        <form className="card" onSubmit={submit} aria-label="Dependent">
          <div className="row">
            <label>First name<input value={form.firstName} required
              onChange={(e) => setForm({ ...form, firstName: e.target.value })} /></label>
            <label>Last name<input value={form.lastName} required
              onChange={(e) => setForm({ ...form, lastName: e.target.value })} /></label>
            <label>Relationship
              <select value={form.relationship} onChange={(e) => setForm({ ...form, relationship: e.target.value })}>
                {RELATIONSHIPS.map((r) => <option key={r} value={r}>{r}</option>)}
              </select>
            </label>
            <label>Date of birth<input type="date" value={form.dateOfBirth ?? ''}
              onChange={(e) => setForm({ ...form, dateOfBirth: e.target.value })} /></label>
            <label>Benefits eligible<input type="checkbox" checked={!!form.benefitsEligible}
              onChange={(e) => setForm({ ...form, benefitsEligible: e.target.checked })} /></label>
          </div>
          <button type="submit">{form.dependentId ? 'Update dependent' : 'Add dependent'}</button>
        </form>
      )}
    </>
  );
}

function Contacts({ empId, canEdit }: { empId: number; canEdit: boolean }) {
  const load = useCallback(() => employeeApi.emergencyContacts(empId), [empId]);
  const { rows, error, reload, setError } = useList<EmergencyContact>(load);
  const empty: EmergencyContact = { contactName: '', relationship: '', phonePrimary: '', email: '', isPrimary: false };
  const [form, setForm] = useState<EmergencyContact>(empty);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    try {
      if (form.contactId) await employeeApi.updateEmergencyContact(empId, form.contactId, form);
      else await employeeApi.addEmergencyContact(empId, form);
      setForm(empty);
      reload();
    } catch (err) {
      setError(errorText(err));
    }
  };
  const remove = async (id: number) => {
    try {
      await employeeApi.deleteEmergencyContact(empId, id);
      reload();
    } catch (err) {
      setError(errorText(err));
    }
  };
  return (
    <>
      {error && <p className="error" role="alert">{error}</p>}
      <table aria-label="Emergency contacts">
        <thead><tr><th>Name</th><th>Relationship</th><th>Phone</th><th>Email</th><th>Primary</th><th /></tr></thead>
        <tbody>
          {rows.map((c) => (
            <tr key={c.contactId}><td>{c.contactName}</td><td>{c.relationship ?? '—'}</td><td>{c.phonePrimary}</td>
              <td>{c.email ?? '—'}</td><td>{c.isPrimary ? 'Yes' : 'No'}</td>
              <td>{canEdit && <>
                <button onClick={() => setForm({ ...c, relationship: c.relationship ?? '', email: c.email ?? '' })}>Edit</button>{' '}
                <button onClick={() => remove(c.contactId!)}>Remove</button></>}</td></tr>
          ))}
        </tbody>
      </table>
      {canEdit && (
        <form className="card" onSubmit={submit} aria-label="Emergency contact">
          <div className="row">
            <label>Contact name<input value={form.contactName} required
              onChange={(e) => setForm({ ...form, contactName: e.target.value })} /></label>
            <label>Relationship<input value={form.relationship ?? ''}
              onChange={(e) => setForm({ ...form, relationship: e.target.value })} /></label>
            <label>Primary phone<input value={form.phonePrimary} required
              onChange={(e) => setForm({ ...form, phonePrimary: e.target.value })} /></label>
            <label>Contact email<input value={form.email ?? ''}
              onChange={(e) => setForm({ ...form, email: e.target.value })} /></label>
            <label>Primary contact<input type="checkbox" checked={!!form.isPrimary}
              onChange={(e) => setForm({ ...form, isPrimary: e.target.checked })} /></label>
          </div>
          <button type="submit">{form.contactId ? 'Update contact' : 'Add contact'}</button>
        </form>
      )}
    </>
  );
}

function Team({ empId }: { empId: number }) {
  const loadReports = useCallback(() => employeeApi.directReports(empId), [empId]);
  const loadChart = useCallback(() => employeeApi.orgChart(empId), [empId]);
  const reports = useList<EmployeeSummary>(loadReports);
  const chart = useList<OrgChartNode>(loadChart);
  return (
    <>
      <h2>Direct reports</h2>
      <ul aria-label="Direct reports">
        {reports.rows.length === 0 && <li>None</li>}
        {reports.rows.map((r) => <li key={r.empId}><Link to={`../${r.empId}`}>{r.fullName}</Link> — {r.jobTitle}</li>)}
      </ul>
      <h2>Organisation below</h2>
      <ul aria-label="Org chart">
        {chart.rows.map((n) => (
          <li key={n.empId} style={{ marginLeft: `${(n.level - 1) * 1.5}rem` }}>
            <Link to={`../${n.empId}`}>{n.fullName}</Link> — {n.jobTitle}
          </li>
        ))}
      </ul>
    </>
  );
}

function Lifecycle({ emp, onDone }: { emp: EmployeeDetail; onDone: (e: EmployeeDetail) => void }) {
  const ref = useRefData();
  const [f, setF] = useState({ deptId: '', jobId: '', locationId: '', managerEmpId: '', date: '', salary: '',
    reason: '' });
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const set = (k: keyof typeof f) => (v: string) => setF((x) => ({ ...x, [k]: v }));
  const run = async (label: string, call: () => Promise<EmployeeDetail>) => {
    try {
      onDone(await call());
      setMsg({ ok: true, text: `${label} complete` });
    } catch (err) {
      setMsg({ ok: false, text: errorText(err) });
    }
  };
  const terminated = emp.employmentStatus === 'TERMINATED';
  return (
    <div className="card">
      <div className="row">
        <RefSelect label="New department" items={ref.departments} value={f.deptId} onChange={set('deptId')} />
        <RefSelect label="New job" items={ref.jobs} value={f.jobId} onChange={set('jobId')} />
        <RefSelect label="New location" items={ref.locations} value={f.locationId} onChange={set('locationId')} />
        <label>New manager ID<input inputMode="numeric" value={f.managerEmpId} onChange={(e) => set('managerEmpId')(e.target.value)} /></label>
      </div>
      <div className="row">
        <label>Effective date<input type="date" value={f.date} onChange={(e) => set('date')(e.target.value)} /></label>
        <label>Salary<input type="number" min="0" value={f.salary} onChange={(e) => set('salary')(e.target.value)} /></label>
        <label>Reason<input value={f.reason} onChange={(e) => set('reason')(e.target.value)} /></label>
      </div>
      <div className="row">
        {!terminated && <>
          <button onClick={() => run('Transfer', () => employeeApi.transfer(emp.empId, {
            deptId: Number(f.deptId || emp.deptId), jobId: optionalNumber(f.jobId),
            locationId: optionalNumber(f.locationId), managerEmpId: optionalNumber(f.managerEmpId),
            effectiveDate: f.date, reason: f.reason || undefined }))}>Transfer</button>
          <button onClick={() => run('Promotion', () => employeeApi.promote(emp.empId, {
            jobId: Number(f.jobId), newSalary: Number(f.salary), effectiveDate: f.date,
            reason: f.reason || undefined }))}>Promote</button>
          <button onClick={() => run('Termination', () => employeeApi.terminate(emp.empId, {
            terminationDate: f.date, reason: f.reason }))}>Terminate</button>
        </>}
        {terminated && (
          <button onClick={() => run('Rehire', () => employeeApi.rehire(emp.empId, {
            hireDate: f.date, deptId: Number(f.deptId || emp.deptId), jobId: Number(f.jobId || emp.jobId),
            managerEmpId: optionalNumber(f.managerEmpId), baseSalary: optionalNumber(f.salary) }))}>Rehire</button>
        )}
      </div>
      {msg && <p className={msg.ok ? 'success' : 'error'} role="status">{msg.text}</p>}
    </div>
  );
}
