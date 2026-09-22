import generated from '../../generated/validation-schema.json';
import { SEED_ACCOUNTS } from '../../../e2e/seed-accounts';
import { evaluateCustomRules, evaluateRules, fieldErrors, getDto, getParameter, validationSchema, zodFor, zodFormFor } from '../schema';

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

  it('snapshot: the DTO/field vocabulary the pages depend on', () => {
    const shape = Object.fromEntries(
      Object.entries(validationSchema.dtos).map(([dto, spec]) => [
        dto,
        Object.fromEntries(Object.entries(spec.fields).map(([f, s]) => [f, { type: s.type, required: s.required, rules: (s.rules ?? []).map((r) => r.errorCode) }])),
      ]),
    );
    expect(shape).toMatchSnapshot();
  });

  it('evaluates the P2 custom rules leave.dateOrder (-20210) and leave.pastLimit (-20211) from the JSON', () => {
    const dto = getDto('LeaveRequestCreateRequest');
    const today = '2026-09-22';
    expect(evaluateCustomRules(dto, { startDate: '2026-09-21', endDate: '2026-09-25' }, { today })).toEqual([]);
    expect(evaluateCustomRules(dto, { startDate: '2026-09-25', endDate: '2026-09-21' }, { today })).toEqual([
      { field: 'endDate', errorCode: '-20210', message: dto.fields.endDate.rules![0].message, ruleId: 'leave.dateOrder' },
    ]);
    expect(evaluateCustomRules(dto, { startDate: '2026-09-17', endDate: '2026-09-17' }, { today })).toEqual([]);
    expect(evaluateCustomRules(dto, { startDate: '2026-09-16', endDate: '2026-09-16' }, { today })).toEqual([
      { field: 'startDate', errorCode: '-20211', message: dto.fields.startDate.rules![0].message, ruleId: 'leave.pastLimit' },
    ]);
  });

  it('zodFormFor(LeaveRequestCreateRequest) reports required + cross-field messages per field', () => {
    const dto = getDto('LeaveRequestCreateRequest');
    const schema = zodFormFor('LeaveRequestCreateRequest', { today: '2026-09-22' });
    const empty = schema.safeParse({ startDate: '', endDate: '' });
    expect(empty.success).toBe(false);
    if (!empty.success) {
      expect(fieldErrors(empty.error)).toEqual({
        leaveTypeId: dto.fields.leaveTypeId.messages.required,
        startDate: dto.fields.startDate.messages.required,
        endDate: dto.fields.endDate.messages.required,
      });
    }
    const reversed = schema.safeParse({ leaveTypeId: 1, startDate: '2026-10-05', endDate: '2026-10-01', halfDayPeriod: 'AM' });
    expect(reversed.success).toBe(false);
    if (!reversed.success) expect(fieldErrors(reversed.error)).toEqual({ endDate: dto.fields.endDate.rules![0].message });
    expect(schema.safeParse({ leaveTypeId: 1, startDate: '2026-10-01', endDate: '2026-10-05', halfDay: false, reason: 'x' }).success).toBe(true);
    expect(schema.safeParse({ leaveTypeId: 1, startDate: '2026-10-01', endDate: '2026-10-01', halfDayPeriod: 'XX' }).success).toBe(false);
  });
});
