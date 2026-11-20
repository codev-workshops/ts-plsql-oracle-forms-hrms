import { api, call, type Schemas } from '../api/client';
import { ActionButton, body, DataTable, ErrorBanner, SimpleForm, useLoad } from '../components/ui';

type Req = Schemas['LeaveRequest'];

/** HRMS_LEAVE: submit, my requests, balances, approval queue. Cancel is offered for PENDING/APPROVED (server gate). */
export function Leave() {
  const types = useLoad(() => call(api.GET('/api/v1/leave/types')), []);
  const balances = useLoad(() => call(api.GET('/api/v1/leave/balances')), []);
  const mine = useLoad(() => call(api.GET('/api/v1/leave/requests')), []);
  const queue = useLoad(() => call(api.GET('/api/v1/leave/approvals')), []);
  const reload = () => {
    balances.reload();
    mine.reload();
    queue.reload();
  };
  const path = (r: Req) => ({ path: { requestId: r.requestId! } });

  return (
    <section>
      <h2>Leave</h2>
      <h3>Balances</h3>
      <ErrorBanner error={balances.error} />
      <DataTable
        label="Leave balances"
        rows={balances.data}
        columns={[
          { header: 'Type', render: (b: Schemas['LeaveBalance']) => b.leaveTypeName },
          { header: 'Year', render: (b) => b.calendarYear },
          { header: 'Used', render: (b) => b.used },
          { header: 'Pending', render: (b) => b.pending },
          { header: 'Available', render: (b) => b.available },
        ]}
      />
      {types.data && (
        <SimpleForm
          title="Submit leave request"
          submitLabel="Submit request"
          fields={[
            {
              name: 'leaveTypeId',
              label: 'Leave type',
              type: 'select',
              required: true,
              options: types.data.map((t) => ({ value: String(t.leaveTypeId), label: t.leaveTypeName ?? '' })),
            },
            { name: 'startDate', label: 'Start date', type: 'date', required: true },
            { name: 'endDate', label: 'End date', type: 'date', required: true },
            { name: 'halfDay', label: 'Half day', type: 'checkbox' },
            {
              name: 'halfDayPeriod',
              label: 'Half-day period',
              type: 'select',
              options: [
                { value: 'AM', label: 'AM' },
                { value: 'PM', label: 'PM' },
              ],
            },
            { name: 'reason', label: 'Reason', type: 'textarea' },
          ]}
          onSubmit={async (v) => {
            await call(
              api.POST('/api/v1/leave/requests', {
                body: body(v, ['leaveTypeId'], ['halfDay']) as Schemas['SubmitLeaveRequest'],
              }),
            );
            reload();
          }}
        />
      )}
      <h3>My requests</h3>
      <ErrorBanner error={mine.error} />
      <DataTable
        label="My requests"
        rows={mine.data}
        columns={[
          { header: 'Type', render: (r: Req) => r.leaveTypeName },
          { header: 'From', render: (r) => r.startDate },
          { header: 'To', render: (r) => r.endDate },
          { header: 'Days', render: (r) => r.totalDays },
          { header: 'Status', render: (r) => r.status },
          {
            header: '',
            render: (r) =>
              (r.status === 'PENDING' || r.status === 'APPROVED') && (
                <ActionButton
                  label="Cancel"
                  onClick={() =>
                    call(api.POST('/api/v1/leave/requests/{requestId}/cancel', { params: path(r), body: {} })).then(reload)
                  }
                />
              ),
          },
        ]}
      />
      <h3>Awaiting my approval</h3>
      <ErrorBanner error={queue.error} />
      <DataTable
        label="Approval queue"
        rows={queue.data}
        empty="Nothing to approve"
        columns={[
          { header: 'Employee', render: (r: Req) => r.employeeName },
          { header: 'Type', render: (r) => r.leaveTypeName },
          { header: 'From', render: (r) => r.startDate },
          { header: 'To', render: (r) => r.endDate },
          { header: 'Days', render: (r) => r.totalDays },
          {
            header: '',
            render: (r) => (
              <>
                <ActionButton
                  label="Approve"
                  onClick={() =>
                    call(api.POST('/api/v1/leave/requests/{requestId}/approve', { params: path(r), body: {} })).then(reload)
                  }
                />
                <ActionButton
                  label="Reject"
                  onClick={() =>
                    call(api.POST('/api/v1/leave/requests/{requestId}/reject', { params: path(r), body: {} })).then(reload)
                  }
                />
              </>
            ),
          },
        ]}
      />
    </section>
  );
}
