import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { PG_PERFORMANCE_FIXTURES, SEED_PASSWORD } from '../../../e2e/seed-accounts';

const here = dirname(fileURLToPath(import.meta.url));
const read = (rel: string) => readFileSync(resolve(here, rel), 'utf8');

/**
 * Real-stack Playwright specs (`npm run e2e:real`) may only depend on rows committed in
 * tools/fixtures/pg. Keeps e2e/seed-accounts.ts PG_PERFORMANCE_FIXTURES honest and prevents
 * the mock-only performance golden path from running against PostgreSQL.
 */
describe('e2e fixtures vs tools/fixtures/pg', () => {
  const transactions = read('../../../../tools/fixtures/pg/03_transaction_data.sql');
  const employees = read('../../../../tools/fixtures/pg/02_employee_data.sql');
  const performanceSpec = read('../../../e2e/performance-golden-path.spec.ts');
  const goldenPathSpec = read('../../../e2e/golden-path.spec.ts');

  it('PG_PERFORMANCE_FIXTURES mirrors the committed review cycle and reviews', () => {
    const { cycle, reviews } = PG_PERFORMANCE_FIXTURES;
    expect(transactions).toMatch(new RegExp(`values \\(${cycle.cycleId}, '${cycle.cycleName}',[^;]*'${cycle.status}'`));
    for (const review of reviews) {
      expect(transactions).toMatch(new RegExp(`values \\(${review.reviewId}, ${cycle.cycleId}, ${review.empId}, ${review.reviewerEmpId}, '[A-Z_]+', '${review.status}'`));
      const [first, last] = review.employeeName.split(' ');
      expect(employees).toMatch(new RegExp(`values \\(${review.empId}, 'EMP-\\d+', '${first}', '${last}'`));
    }
  });

  it('msw-only performance fixtures are never referenced by a real-stack performance spec', () => {
    const mockOnlyLiterals = ['FY2025 Annual Review', 'EMILY JOHNSON', 'Open cycle'];
    const [, mockBlock = '', realBlock = ''] = performanceSpec.split(/test\.describe\('[^']*\((?:mock|real) stack\)'/);
    expect(mockBlock).toContain("test.skip(realStack");
    expect(realBlock).toContain('test.skip(!realStack');
    for (const literal of mockOnlyLiterals) expect(realBlock).not.toContain(literal);
    expect(realBlock).toContain('PG_PERFORMANCE_FIXTURES');
  });

  it('P0 change-password spec restores the committed seed password', () => {
    expect(SEED_PASSWORD).toBe('Welcome1!');
    const changePasswordTest = goldenPathSpec.slice(goldenPathSpec.indexOf("test('change password"), goldenPathSpec.indexOf("test('first-login"));
    expect(changePasswordTest).toContain("getByLabel('Current password').fill(ROTATED_PASSWORD)");
    expect(changePasswordTest).toContain("getByLabel('New password', { exact: true }).fill(SEED_PASSWORD)");
  });
});
