import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HttpResponse, http } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { SEED_ACCOUNTS } from '../../../../e2e/seed-accounts';
import { server } from '../../../mocks/server';
import { getDto } from '../../../validation/schema';
import { SubmitRequestTab } from '../SubmitRequestTab';
import { iso, renderLeaveAs, weekday } from './leaveTestUtils';

const ui = (
  <Routes>
    <Route path="/leave" element={<h2>My Requests landing</h2>} />
    <Route path="/leave/submit" element={<SubmitRequestTab />} />
  </Routes>
);

const rules = getDto('LeaveRequestCreateRequest').fields;

async function fillDates(user: ReturnType<typeof userEvent.setup>, start: string, end: string) {
  await user.clear(screen.getByLabelText('Start date'));
  await user.type(screen.getByLabelText('Start date'), start);
  await user.clear(screen.getByLabelText('End date'));
  await user.type(screen.getByLabelText('End date'), end);
}

describe('SubmitRequestTab', () => {
  it('shows generated required messages when submitted empty (no rule duplicated in the component)', async () => {
    const user = userEvent.setup();
    renderLeaveAs(SEED_ACCOUNTS.staff.email, ui, '/leave/submit');
    await screen.findByRole('option', { name: 'Paid Time Off' });
    await user.click(screen.getByRole('button', { name: 'Submit Request' }));
    const alerts = (await screen.findAllByRole('alert')).map((a) => a.textContent);
    expect(alerts).toEqual(expect.arrayContaining([rules.leaveTypeId.messages.required, rules.startDate.messages.required, rules.endDate.messages.required]));
  });

  it('applies the exported leave.dateOrder (-20210) and leave.pastLimit (-20211) custom rules client-side', async () => {
    const user = userEvent.setup();
    renderLeaveAs(SEED_ACCOUNTS.staff.email, ui, '/leave/submit');
    await user.selectOptions(screen.getByLabelText(/Leave type/, { selector: 'select' }), await screen.findByRole('option', { name: 'Paid Time Off' }));
    await fillDates(user, iso(10), iso(8));
    expect(screen.getByTestId('business-days')).toHaveTextContent('—');
    await user.click(screen.getByRole('button', { name: 'Submit Request' }));
    expect(await screen.findByText(rules.endDate.rules![0].message)).toBeInTheDocument();

    await fillDates(user, iso(-(Number(rules.startDate.rules![0].value) + 1)), iso(1));
    await user.click(screen.getByRole('button', { name: 'Submit Request' }));
    expect(await screen.findByText(rules.startDate.rules![0].message)).toBeInTheDocument();
  });

  it('shows the live business-day count, excluded holidays and the available balance for the chosen type', async () => {
    const user = userEvent.setup();
    server.use(
      http.get('/api/leave/business-days', ({ request }) => {
        const url = new URL(request.url);
        return HttpResponse.json({ start: url.searchParams.get('start'), end: url.searchParams.get('end'), businessDays: 4, holidays: [{ holidayName: 'Founders Day', holidayDate: '2026-07-04', observedDate: '2026-07-03' }] });
      }),
    );
    renderLeaveAs(SEED_ACCOUNTS.staff.email, ui, '/leave/submit');
    expect(screen.getByTestId('available-balance')).toHaveTextContent('—');
    await user.selectOptions(screen.getByLabelText(/Leave type/, { selector: 'select' }), await screen.findByRole('option', { name: 'Paid Time Off' }));
    await waitFor(() => expect(screen.getByTestId('available-balance')).toHaveTextContent('11.25 day(s) of Paid Time Off'));
    await fillDates(user, iso(7), iso(13));
    await waitFor(() => expect(screen.getByTestId('business-days')).toHaveTextContent('4'));
    expect(screen.getByText(/Founders Day \(07\/03\/2026\)/)).toBeInTheDocument();
  });

  it('half day locks the end date to the start date, counts 0.5 and requires AM/PM from the schema', async () => {
    const user = userEvent.setup();
    renderLeaveAs(SEED_ACCOUNTS.staff.email, ui, '/leave/submit');
    await user.selectOptions(screen.getByLabelText(/Leave type/, { selector: 'select' }), await screen.findByRole('option', { name: 'Paid Time Off' }));
    const day = weekday(20);
    await user.type(screen.getByLabelText('Start date'), day);
    await user.click(screen.getByLabelText('Half day'));
    expect(screen.getByLabelText('End date')).toHaveValue(day);
    expect(screen.getByLabelText('End date')).toBeDisabled();
    expect(screen.getByTestId('business-days')).toHaveTextContent('0.5');
    await user.click(screen.getByRole('button', { name: 'Submit Request' }));
    expect(await screen.findByText(rules.halfDayPeriod.messages.required!)).toBeInTheDocument();
    const periods = within(screen.getByLabelText('Half-day period')).getAllByRole('option').map((o) => o.textContent);
    expect(periods).toEqual(['— Select —', ...rules.halfDayPeriod.values!]);
  });

  it('submits a valid request, toasts, and returns to My Requests', async () => {
    const user = userEvent.setup();
    renderLeaveAs(SEED_ACCOUNTS.staff.email, ui, '/leave/submit');
    await user.selectOptions(screen.getByLabelText(/Leave type/, { selector: 'select' }), await screen.findByRole('option', { name: 'Paid Time Off' }));
    const start = weekday(30);
    await fillDates(user, start, start);
    await waitFor(() => expect(screen.getByTestId('business-days')).toHaveTextContent('1'));
    await user.type(screen.getByLabelText('Reason'), 'Dentist');
    await user.click(screen.getByRole('button', { name: 'Submit Request' }));
    expect(await screen.findByText('Leave request submitted for approval')).toBeInTheDocument();
    expect(await screen.findByText('My Requests landing')).toBeInTheDocument();
  });

  it('shows the auto-approval hint for non-approval types and the auto-approved toast', async () => {
    const user = userEvent.setup();
    renderLeaveAs(SEED_ACCOUNTS.staff.email, ui, '/leave/submit');
    await user.selectOptions(screen.getByLabelText(/Leave type/, { selector: 'select' }), await screen.findByRole('option', { name: 'Sick Leave' }));
    expect(await screen.findByText('This leave type is auto-approved on submission.')).toBeInTheDocument();
    expect(screen.getByText('Supporting documentation is required for this leave type.')).toBeInTheDocument();
    const start = weekday(40);
    await fillDates(user, start, start);
    await user.click(screen.getByRole('button', { name: 'Submit Request' }));
    expect(await screen.findByText('Leave request submitted and auto-approved')).toBeInTheDocument();
  });

  it('maps server ApiError codes onto fields: -20202 overlap → startDate, -20201 balance → leaveTypeId', async () => {
    const user = userEvent.setup();
    renderLeaveAs(SEED_ACCOUNTS.staff.email, ui, '/leave/submit');
    await user.selectOptions(screen.getByLabelText(/Leave type/, { selector: 'select' }), await screen.findByRole('option', { name: 'Paid Time Off' }));
    // 902 in the store spans iso(14)..iso(15) for the staff user
    await fillDates(user, iso(14), iso(15));
    await user.click(screen.getByRole('button', { name: 'Submit Request' }));
    const startField = screen.getByLabelText('Start date').closest<HTMLElement>('.field')!;
    expect(await within(startField).findByRole('alert')).toHaveTextContent('Leave request overlaps with an existing request');

    server.use(
      http.post('/api/leave/requests', () =>
        HttpResponse.json({ code: '-20201', message: 'Insufficient leave balance. Available: 11.25, Requested: 20', traceId: 't' }, { status: 422 }),
      ),
    );
    await fillDates(user, iso(60), iso(90));
    await user.click(screen.getByRole('button', { name: 'Submit Request' }));
    const typeField = screen.getByLabelText(/Leave type/, { selector: 'select' }).closest<HTMLElement>('.field')!;
    expect(await within(typeField).findByRole('alert')).toHaveTextContent('Insufficient leave balance. Available: 11.25, Requested: 20');
  });

  it('warns before submission when the requested days exceed the available balance', async () => {
    const user = userEvent.setup();
    server.use(http.get('/api/leave/business-days', () => HttpResponse.json({ start: '', end: '', businessDays: 15, holidays: [] })));
    renderLeaveAs(SEED_ACCOUNTS.staff.email, ui, '/leave/submit');
    await user.selectOptions(screen.getByLabelText(/Leave type/, { selector: 'select' }), await screen.findByRole('option', { name: 'Paid Time Off' }));
    await fillDates(user, iso(60), iso(80));
    expect(await screen.findByText(/exceed the available balance/)).toBeInTheDocument();
  });
});
