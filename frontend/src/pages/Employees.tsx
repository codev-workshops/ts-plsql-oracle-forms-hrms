import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, call, type Schemas } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { body, DataTable, ErrorBanner, SimpleForm, useLoad } from '../components/ui';
import { emailHint, phoneHint } from '../validation';

type Employee = Schemas['EmployeeView'];

function useLookups() {
  return useLoad(
    async () => {
      const [departments, jobs, locations] = await Promise.all([
        call(api.GET('/api/v1/lookups/departments')),
        call(api.GET('/api/v1/lookups/job-titles')),
        call(api.GET('/api/v1/lookups/locations')),
      ]);
      return {
        departments: departments.map((d) => ({ value: String(d.deptId), label: d.deptName })),
        jobs: jobs.map((j) => ({ value: String(j.jobId), label: j.jobTitle })),
        locations: locations.map((l) => ({ value: l.locationCode, label: l.locationName })),
      };
    },
    [],
  );
}

/** HRMS_EMPLOYEE search block: active-only by default (DEFAULT_WHERE, server-side). */
export function Employees() {
  const { has } = useAuth();
  const [lastName, setLastName] = useState('');
  const [includeInactive, setIncludeInactive] = useState(false);
  const [query, setQuery] = useState({ lastName: '', includeInactive: false });
  const lookups = useLookups();
  const list = useLoad(
    () =>
      call(
        api.GET('/api/v1/employees', {
          params: { query: { lastName: query.lastName || undefined, includeInactive: query.includeInactive, size: 50 } },
        }),
      ),
    [query],
  );

  return (
    <section>
      <h2>Employees</h2>
      <form
        className="row"
        aria-label="Search employees"
        onSubmit={(e) => {
          e.preventDefault();
          setQuery({ lastName, includeInactive });
        }}
      >
        <label>
          Last name <input value={lastName} onChange={(e) => setLastName(e.target.value)} />
        </label>
        <label>
          <input type="checkbox" checked={includeInactive} onChange={(e) => setIncludeInactive(e.target.checked)} />{' '}
          Include inactive
        </label>
        <button type="submit">Search</button>
      </form>
      <ErrorBanner error={list.error} />
      {list.data && <p className="muted">{list.data.total} employee(s)</p>}
      <DataTable
        label="Employees"
        rows={list.data?.items}
        columns={[
          { header: 'Number', render: (e: Employee) => <Link to={`/employees/${e.empId}`}>{e.empNumber}</Link> },
          { header: 'Name', render: (e) => `${e.lastName}, ${e.firstName}` },
          { header: 'Department', render: (e) => e.deptName },
          { header: 'Job', render: (e) => e.jobTitle },
          { header: 'Status', render: (e) => e.employmentStatus },
          { header: 'Hire date', render: (e) => e.hireDate },
        ]}
      />
      {has('EMPLOYEE', 'EDIT') && lookups.data && (
        <SimpleForm
          title="New employee"
          submitLabel="Create employee"
          fields={[
            { name: 'firstName', label: 'First name', required: true },
            { name: 'lastName', label: 'Last name', required: true },
            { name: 'email', label: 'Email', type: 'email', hint: emailHint },
            { name: 'phoneWork', label: 'Work phone', hint: phoneHint },
            { name: 'hireDate', label: 'Hire date', type: 'date', required: true },
            { name: 'deptId', label: 'Department', type: 'select', required: true, options: lookups.data.departments },
            { name: 'jobId', label: 'Job', type: 'select', required: true, options: lookups.data.jobs },
            { name: 'locationCode', label: 'Location', type: 'select', options: lookups.data.locations },
            { name: 'managerEmpId', label: 'Manager emp id', type: 'number' },
            { name: 'baseSalary', label: 'Base salary', type: 'number' },
          ]}
          onSubmit={async (v) => {
            await call(
              api.POST('/api/v1/employees', {
                body: body(v, ['deptId', 'jobId', 'managerEmpId', 'baseSalary']) as Schemas['CreateEmployeeRequest'],
              }),
            );
            list.reload();
          }}
        />
      )}
    </section>
  );
}

export function EmployeeDetail() {
  const { empId } = useParams();
  const id = Number(empId);
  const { has } = useAuth();
  const canEdit = has('EMPLOYEE', 'EDIT');
  const lookups = useLookups();
  const [warnings, setWarnings] = useState<Schemas['Warning'][]>([]);
  const emp = useLoad(
    () => call(api.GET('/api/v1/employees/{empId}', { params: { path: { empId: id }, query: { includeInactive: true } } })),
    [id],
  );
  const history = useLoad(
    () => (canEdit ? call(api.GET('/api/v1/employees/{empId}/history', { params: { path: { empId: id } } })) : Promise.resolve([])),
    [id, canEdit],
  );
  const salary = useLoad(
    () =>
      has('PAYROLL', 'VIEW')
        ? call(api.GET('/api/v1/payroll/employees/{empId}/salary-records', { params: { path: { empId: id } } }))
        : Promise.resolve([]),
    [id],
  );

  async function mutate(p: Promise<Schemas['EmployeeMutation']>) {
    const res = await p;
    setWarnings(res.warnings);
    emp.reload();
    history.reload();
    salary.reload();
  }

  const e = emp.data;
  const terminated = e?.employmentStatus === 'TERMINATED';
  const path = { path: { empId: id } };
  const opts = lookups.data;
  return (
    <section>
      <h2>Employee {e?.empNumber}</h2>
      <ErrorBanner error={emp.error} />
      {e && (
        <dl>
          <dt>Name</dt>
          <dd>
            {e.firstName} {e.lastName}
          </dd>
          <dt>Status</dt>
          <dd>{e.employmentStatus}</dd>
          <dt>Department / job</dt>
          <dd>
            {e.deptName} / {e.jobTitle}
          </dd>
          <dt>Manager</dt>
          <dd>{e.managerName ?? '—'}</dd>
          <dt>Email</dt>
          <dd>{e.email ?? '—'}</dd>
          <dt>Hire date</dt>
          <dd>{e.hireDate}</dd>
        </dl>
      )}
      {warnings.length > 0 && (
        <ul aria-label="Warnings">
          {warnings.map((w) => (
            <li key={w.code}>{w.message}</li>
          ))}
        </ul>
      )}
      {e && canEdit && opts && (
        <div className="row">
          {!terminated && (
            <>
              <SimpleForm
                title="Update"
                submitLabel="Save"
                initial={{ firstName: e.firstName, lastName: e.lastName, email: e.email ?? '' }}
                fields={[
                  { name: 'firstName', label: 'First name' },
                  { name: 'lastName', label: 'Last name' },
                  { name: 'email', label: 'Email', type: 'email', hint: emailHint },
                  { name: 'phoneWork', label: 'Work phone', hint: phoneHint },
                  { name: 'phoneMobile', label: 'Mobile phone', hint: phoneHint },
                ]}
                onSubmit={(v) =>
                  mutate(call(api.PATCH('/api/v1/employees/{empId}', { params: path, body: body(v) as Schemas['UpdateEmployeeRequest'] })))
                }
              />
              <SimpleForm
                title="Transfer"
                submitLabel="Transfer"
                fields={[
                  { name: 'newDeptId', label: 'New department', type: 'select', required: true, options: opts.departments },
                  { name: 'newJobId', label: 'New job', type: 'select', options: opts.jobs },
                  { name: 'newManagerEmpId', label: 'New manager emp id', type: 'number' },
                  { name: 'effectiveDate', label: 'Effective date', type: 'date' },
                ]}
                onSubmit={(v) =>
                  mutate(
                    call(
                      api.POST('/api/v1/employees/{empId}/transfer', {
                        params: path,
                        body: body(v, ['newDeptId', 'newJobId', 'newManagerEmpId']) as Schemas['TransferRequest'],
                      }),
                    ),
                  )
                }
              />
              <SimpleForm
                title="Promote"
                submitLabel="Promote"
                fields={[
                  { name: 'newJobId', label: 'New job', type: 'select', required: true, options: opts.jobs },
                  { name: 'newSalary', label: 'New salary', type: 'number', required: true },
                  { name: 'effectiveDate', label: 'Effective date', type: 'date' },
                ]}
                onSubmit={(v) =>
                  mutate(
                    call(
                      api.POST('/api/v1/employees/{empId}/promote', {
                        params: path,
                        body: body(v, ['newJobId', 'newSalary']) as Schemas['PromoteRequest'],
                      }),
                    ),
                  )
                }
              />
              <SimpleForm
                title="Terminate"
                submitLabel="Terminate"
                fields={[
                  { name: 'terminationDate', label: 'Termination date', type: 'date', required: true },
                  { name: 'reason', label: 'Reason', required: true },
                ]}
                onSubmit={(v) =>
                  mutate(call(api.POST('/api/v1/employees/{empId}/terminate', { params: path, body: body(v) as Schemas['TerminateRequest'] })))
                }
              />
            </>
          )}
          {terminated && (
            <SimpleForm
              title="Rehire"
              submitLabel="Rehire"
              fields={[
                { name: 'rehireDate', label: 'Rehire date', type: 'date', required: true },
                { name: 'deptId', label: 'Department', type: 'select', required: true, options: opts.departments },
                { name: 'jobId', label: 'Job', type: 'select', required: true, options: opts.jobs },
                { name: 'baseSalary', label: 'Base salary', type: 'number', required: true },
              ]}
              onSubmit={(v) =>
                mutate(
                  call(
                    api.POST('/api/v1/employees/{empId}/rehire', {
                      params: path,
                      body: body(v, ['deptId', 'jobId', 'baseSalary']) as Schemas['RehireRequest'],
                    }),
                  ),
                )
              }
            />
          )}
        </div>
      )}
      {canEdit && (
        <>
          <h3>History</h3>
          <ErrorBanner error={history.error} />
          <DataTable
            label="History"
            rows={history.data}
            columns={[
              { header: 'Change', render: (h: Schemas['EmployeeHistoryEntry']) => h.changeType },
              { header: 'Effective', render: (h) => h.effectiveDate },
              { header: 'Reason', render: (h) => h.reasonCode ?? '' },
              { header: 'Comments', render: (h) => h.comments ?? '' },
            ]}
          />
        </>
      )}
      {has('PAYROLL', 'VIEW') && (
        <>
          <h3>Salary records</h3>
          <DataTable
            label="Salary records"
            rows={salary.data}
            columns={[
              { header: 'Effective', render: (s: Schemas['SalaryRecord']) => s.effectiveDate },
              { header: 'End', render: (s) => s.endDate ?? '' },
              { header: 'Base salary', render: (s) => s.baseSalary },
              { header: 'Reason', render: (s) => s.changeReason ?? '' },
              { header: 'Active', render: (s) => (s.active ? 'Y' : 'N') },
            ]}
          />
        </>
      )}
    </section>
  );
}
