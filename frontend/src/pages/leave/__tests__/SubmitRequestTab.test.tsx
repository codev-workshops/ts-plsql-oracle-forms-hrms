import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { SEED_ACCOUNTS } from '../../../../e2e/seed-accounts';
import { addDays, mondayPlusWeeks } from '../../../mocks/leaveStore';
import { getDto } from '../../../validation/schema';
import { LeavePage } from '../LeavePage';
import { SubmitRequestTab } from '../SubmitRequestTab';
import { renderAs } from './helpers';

const dto = getDto('LeaveRequestCreateRequest');

async function pickType(user: ReturnType<typeof userEvent.setup>, name: string) {
  const select = await screen.findByLabelText(/Leave type/);
  await user.selectOptions(select, await screen.findByRole('option', { name }));
}

describe('SubmitRequestTab', () => {
  it('shows required-field messages from validation-schema.json without calling the server', async () => {
    const user = userEvent.setup();
    renderAs(SEED_ACCOUNTS.staff.email, <SubmitRequestTab />, '/leave/submit');
    await user.click(await screen.findByRole('button', { name: 'Submit Request' }));
    expect(await screen.findByText(dto.fields.leaveTypeId.messages.required!)).toBeInTheDocument();
    expect(screen.getByText(dto.fields.startDate.messages.required!)).toBeInTheDocument();
    expect(screen.getByText(dto.fields.endDate.messages.required!)).toBeInTheDocument();
  });

  it('applies the generated leave.dateOrder rule (-20210) on endDate client-side', async () => {
    const user = userEvent.setup();
    renderAs(SEED_ACCOUNTS.staff.email, <SubmitRequestTab />, '/leave/submit');
    await pickType(user, 'Paid Time Off');
    const monday = mondayPlusWeeks(6);
    await user.type(screen.getByLabelText('Start date *'), addDays(monday, 2));
    await user.type(screen.getByLabelText('End date *'), monday);
    expect(screen.getByTestId('business-days')).toHaveTextContent('—');
    await user.click(screen.getByRole('button', { name: 'Submit Request' }));
    const rule = dto.fields.endDate.rules!.find((r) => r.id === 'leave.dateOrder')!;
    expect(await screen.findByText(rule.message)).toBeInTheDocument();
  });

  it('applies the generated leave.pastLimit rule (-20211) on startDate client-side', async () => {
    const user = userEvent.setup();
    renderAs(SEED_ACCOUNTS.staff.email, <SubmitRequestTab />, '/leave/submit');
    await pickType(user, 'Paid Time Off');
    const old = mondayPlusWeeks(-8);
    await user.type(screen.getByLabelText('Start date *'), old);
    await user.type(screen.getByLabelText('End date *'), old);
    await user.click(screen.getByRole('button', { name: 'Submit Request' }));
    const rule = dto.fields.startDate.rules!.find((r) => r.id === 'leave.pastLimit')!;
    expect(await screen.findByText(rule.message)).toBeInTheDocument();
  });

  it('shows live business days (holidays observed) and the available balance for the chosen type', async () => {
    const user = userEvent.setup();
    renderAs(SEED_ACCOUNTS.staff.email, <SubmitRequestTab />, '/leave/submit');
    await pickType(user, 'Paid Time Off');
    await waitFor(() => expect(screen.getByTestId('available-balance')).toHaveTextContent('10'));
    const monday = mondayPlusWeeks(6);
    await user.type(screen.getByLabelText('Start date *'), monday);
    await user.type(screen.getByLabelText('End date *'), addDays(monday, 6));
    await waitFor(() => expect(screen.getByTestId('business-days')).toHaveTextContent(/^5/));

    await pickType(user, 'Sick Leave');
    await waitFor(() => expect(screen.getByTestId('available-balance')).toHaveTextContent('4'));
    await pickType(user, 'Jury Duty');
    await waitFor(() => expect(screen.getByTestId('available-balance')).toHaveTextContent('n/a'));
  });

  it('half day forces end = start, requires AM/PM and counts 0.5 day', async () => {
    const user = userEvent.setup();
    renderAs(SEED_ACCOUNTS.staff.email, <SubmitRequestTab />, '/leave/submit');
    await pickType(user, 'Paid Time Off');
    const monday = mondayPlusWeeks(6);
    await user.type(screen.getByLabelText('Start date *'), monday);
    await user.click(screen.getByRole('checkbox', { name: 'Half day' }));
    expect(screen.getByLabelText('End date *')).toBeDisabled();
    expect(screen.getByLabelText('End date *')).toHaveValue(monday);
    await waitFor(() => expect(screen.getByTestId('business-days')).toHaveTextContent('0.5'));
    await user.click(screen.getByRole('button', { name: 'Submit Request' }));
    expect(await screen.findByText(dto.fields.halfDayPeriod.messages.required!)).toBeInTheDocument();
    const periods = screen.getAllByRole('option').filter((o) => (dto.fields.halfDayPeriod.values ?? []).includes(o.textContent ?? ''));
    expect(periods.map((o) => o.textContent)).toEqual(dto.fields.halfDayPeriod.values);
  });

  it('submits through POST /api/leave/requests and lands on My Requests with the new PENDING row', async () => {
    const user = userEvent.setup();
    renderAs(SEED_ACCOUNTS.staff.email, <LeavePage />, '/leave/submit');
    await pickType(user, 'Paid Time Off');
    const monday = mondayPlusWeeks(6);
    await user.type(screen.getByLabelText('Start date *'), monday);
    await user.type(screen.getByLabelText('End date *'), addDays(monday, 1));
    await user.type(screen.getByLabelText('Reason'), 'Long weekend');
    await user.click(screen.getByRole('button', { name: 'Submit Request' }));
    await screen.findByText('Leave request submitted');
    await screen.findByRole('table', { name: 'My leave requests' });
    expect(await screen.findByText('Long weekend')).toBeInTheDocument();
    // pending 3 + 2 → available 8
    await waitFor(() => expect(screen.getByTestId('available-PTO')).toHaveTextContent('8'));
  });

  it('auto-approves leave types with requiresApproval=false (LEGACY-DEFECT-AUTOAPPROVE target)', async () => {
    const user = userEvent.setup();
    renderAs(SEED_ACCOUNTS.staff.email, <SubmitRequestTab />, '/leave/submit');
    await pickType(user, 'Sick Leave');
    const monday = mondayPlusWeeks(6);
    await user.type(screen.getByLabelText('Start date *'), monday);
    await user.type(screen.getByLabelText('End date *'), monday);
    await user.click(screen.getByRole('button', { name: 'Submit Request' }));
    await screen.findByText('Leave request auto-approved');
  });

  it('maps server -20202 (overlap) to the startDate field via useErrorHandler', async () => {
    const user = userEvent.setup();
    renderAs(SEED_ACCOUNTS.staff.email, <SubmitRequestTab />, '/leave/submit');
    await pickType(user, 'Paid Time Off');
    const w2 = mondayPlusWeeks(2); // seed 3001 = w2..w2+2 PENDING
    await user.type(screen.getByLabelText('Start date *'), addDays(w2, 1));
    await user.type(screen.getByLabelText('End date *'), addDays(w2, 1));
    await user.click(screen.getByRole('button', { name: 'Submit Request' }));
    expect(await screen.findByText('Leave request overlaps with existing request')).toBeInTheDocument();
    expect(screen.getByLabelText('Start date *')).toHaveAttribute('aria-invalid', 'true');
  });

  it('maps server -20201 (insufficient balance) to the leaveTypeId field', async () => {
    const user = userEvent.setup();
    renderAs(SEED_ACCOUNTS.staff.email, <SubmitRequestTab />, '/leave/submit');
    await pickType(user, 'Paid Time Off');
    const monday = mondayPlusWeeks(8);
    await user.type(screen.getByLabelText('Start date *'), monday);
    await user.type(screen.getByLabelText('End date *'), addDays(monday, 18)); // 15 business days > 10 available
    await waitFor(() => expect(screen.getByTestId('business-days')).toHaveTextContent(/^15/));
    await user.click(screen.getByRole('button', { name: 'Submit Request' }));
    expect(await screen.findByText(/Insufficient leave balance/)).toBeInTheDocument();
  });

  it('non-accrual types skip the balance check (FMLA with sufficient tenure is accepted as PENDING)', async () => {
    const user = userEvent.setup();
    renderAs(SEED_ACCOUNTS.staff.email, <SubmitRequestTab />, '/leave/submit');
    await pickType(user, 'Family Medical Leave');
    const monday = mondayPlusWeeks(6);
    await user.type(screen.getByLabelText('Start date *'), monday);
    await user.type(screen.getByLabelText('End date *'), monday);
    await user.click(screen.getByRole('button', { name: 'Submit Request' }));
    await screen.findByText('Leave request submitted');
  });

  it('maps server -20212 (no business day) to startDate for a weekend half-day', async () => {
    const user = userEvent.setup();
    renderAs(SEED_ACCOUNTS.staff.email, <SubmitRequestTab />, '/leave/submit');
    await pickType(user, 'Paid Time Off');
    const saturday = addDays(mondayPlusWeeks(6), 5);
    await user.type(screen.getByLabelText('Start date *'), saturday);
    await user.click(screen.getByRole('checkbox', { name: 'Half day' }));
    await user.selectOptions(screen.getByLabelText('Period *'), 'AM');
    await user.click(screen.getByRole('button', { name: 'Submit Request' }));
    expect(await screen.findByText('Leave request must include at least one business day')).toBeInTheDocument();
  });
});
