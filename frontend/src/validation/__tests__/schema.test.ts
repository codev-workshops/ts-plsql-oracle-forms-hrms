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

  it('snapshot: the DTO/field vocabulary the pages depend on', () => {
    const snapshotDtos = new Set(['ChangePasswordRequest', 'EmployeeSearchQuery', 'LoginRequest', 'SsoExchangeRequest']);
    const shape = Object.fromEntries(
      Object.entries(validationSchema.dtos)
        .filter(([dto]) => snapshotDtos.has(dto))
        .map(([dto, spec]) => [
        dto,
        Object.fromEntries(Object.entries(spec.fields).map(([f, s]) => [f, { type: s.type, required: s.required, rules: (s.rules ?? []).map((r) => r.errorCode) }])),
        ]),
    );
    expect(shape).toMatchSnapshot();
  });
});
