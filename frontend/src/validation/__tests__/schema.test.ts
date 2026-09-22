import generated from '../../generated/validation-schema.json';
import { SEED_ACCOUNTS } from '../../../e2e/seed-accounts';
import { evaluateRules, fieldErrors, getDto, getParameter, validationSchema, zodFor } from '../schema';

describe('validation-schema adapter', () => {
  it('loads the generated envelope verbatim', () => {
    expect(validationSchema).toBe(generated);
    expect(getParameter('SECURITY.PASSWORD_MIN_LENGTH')).toBe(8);
  });

  it('builds a Zod schema for LoginRequest from the JSON (trim + required + format)', () => {
    const schema = zodFor('LoginRequest');
    const ok = schema.safeParse({ username: `  ${SEED_ACCOUNTS.executive.email} `, password: 'x' });
    expect(ok.success).toBe(true);
    if (ok.success) expect(ok.data.username).toBe(SEED_ACCOUNTS.executive.email);

    const bad = schema.safeParse({ username: '', password: '' });
    expect(bad.success).toBe(false);
    if (!bad.success) expect(fieldErrors(bad.error)).toEqual({ username: 'Username is required', password: 'Password is required' });

    const fmt = schema.safeParse({ username: 'nope', password: 'x' });
    if (!fmt.success) expect(fieldErrors(fmt.error).username).toBe('Enter a valid e-mail address');
  });

  it('evaluates custom rules in exported order and reports the legacy error code', () => {
    const field = getDto('ChangePasswordRequest').fields.newPassword;
    expect(evaluateRules(field, 'short')?.errorCode).toBe('-20310');
    expect(evaluateRules(field, 'lowercase1')?.errorCode).toBe('-20311');
    expect(evaluateRules(field, 'NoDigitsHere')?.errorCode).toBe('-20312');
    expect(evaluateRules(field, 'GoodPass1')).toBeNull();
  });

  it('the ChangePasswordRequest Zod schema surfaces the first failing rule message only', () => {
    const r = zodFor('ChangePasswordRequest').safeParse({ currentPassword: 'a', newPassword: 'lowercase1' });
    expect(r.success).toBe(false);
    if (!r.success) expect(fieldErrors(r.error)).toEqual({ newPassword: 'Password must contain an uppercase letter' });
  });

  it('evaluates the P2 cross-field custom rules from the JSON operands (leave.dateOrder / leave.pastLimit)', () => {
    const schema = zodFor('LeaveRequestCreateRequest');
    const day = (offset: number) => new Date(Date.now() + offset * 86_400_000).toISOString().slice(0, 10);
    const dto = getDto('LeaveRequestCreateRequest').fields;

    const order = schema.safeParse({ leaveTypeId: 1, startDate: day(10), endDate: day(9) });
    expect(order.success).toBe(false);
    if (!order.success) {
      expect(fieldErrors(order.error)).toEqual({ endDate: dto.endDate.rules![0].message });
      expect(order.error.issues[0].path).toEqual(['endDate']);
    }

    const limit = Number(dto.startDate.rules![0].value);
    const past = schema.safeParse({ leaveTypeId: 1, startDate: day(-(limit + 2)), endDate: day(1) });
    expect(past.success).toBe(false);
    if (!past.success) expect(fieldErrors(past.error)).toEqual({ startDate: dto.startDate.rules![0].message });

    expect(schema.safeParse({ leaveTypeId: 1, startDate: day(-limit), endDate: day(1) }).success).toBe(true);
    expect(schema.safeParse({ leaveTypeId: '', startDate: day(1), endDate: day(1) }).success).toBe(false);
    const missingType = schema.safeParse({ leaveTypeId: '', startDate: day(1), endDate: day(1) });
    if (!missingType.success) expect(fieldErrors(missingType.error).leaveTypeId).toBe(dto.leaveTypeId.messages.required);

    const half = schema.safeParse({ leaveTypeId: 1, startDate: day(1), endDate: day(2), halfDay: true });
    expect(half.success).toBe(false);
    if (!half.success) expect(fieldErrors(half.error)).toEqual({ endDate: dto.halfDay.messages.format, halfDayPeriod: dto.halfDayPeriod.messages.required });
    expect(schema.safeParse({ leaveTypeId: 1, startDate: day(1), endDate: day(1), halfDay: true, halfDayPeriod: 'PM' }).success).toBe(true);

    expect(zodFor('LeaveRejectRequest').safeParse({ comments: '  ' }).success).toBe(false);
    expect(zodFor('LeaveApproveRequest').safeParse({}).success).toBe(true);
  });

  it('snapshot: the DTO/field vocabulary the pages depend on', () => {
    const shape = Object.fromEntries(
      Object.entries(validationSchema.dtos).map(([dto, spec]) => [
        dto,
        Object.fromEntries(Object.entries(spec.fields).map(([f, s]) => [f, { type: s.type, required: s.required, rules: (s.rules ?? []).map((r) => r.errorCode) }])),
      ]),
    );
    expect(shape).toMatchSnapshot();
  });
});
