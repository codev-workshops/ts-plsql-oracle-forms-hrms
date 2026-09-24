import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { api } from '../../api/client';
import { ROLE_AUTHORITIES, SEED_ACCOUNTS, SEED_PASSWORD } from '../../../e2e/seed-accounts';

describe('committed seed accounts', () => {
  for (const account of Object.values(SEED_ACCOUNTS)) {
    it(`logs in ${account.email} with the committed password`, async () => {
      const { user } = await api.auth.login({ username: account.email, password: SEED_PASSWORD });
      expect(user.email).toBe(account.email);
      expect(user.displayName).toBe(account.displayName);
      expect(user.mustChangePassword).toBe(account.mustChangePassword);
      expect(user.roles).toEqual(ROLE_AUTHORITIES[account.role]);
    });
  }

  it('does not retain the removed mock identities or password literal in golden-path e2e', () => {
    const spec = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), '../../../e2e/golden-path.spec.ts'), 'utf8');
    expect(spec).not.toContain('hrms.example');
    expect(spec).not.toContain("'Welcome1'");
  });
});
