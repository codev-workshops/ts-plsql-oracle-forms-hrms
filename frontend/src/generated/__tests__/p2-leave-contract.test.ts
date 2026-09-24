/**
 * Pins the PKG_LEAVE side effects frozen in contracts/p2-leave/openapi.yaml that the Level 2
 * parallel run compares (TEST_STRATEGY.md §2.2 step 3: AUDIT_LOG action + table,
 * NOTIFICATION_QUEUE type + recipient) and the notification bodies / carryover formula the
 * contract declares byte-for-byte with PKG_LEAVE.pkb. The file is read as text on purpose:
 * the assertions are on the frozen wording, not on a parsed model.
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const root = resolve(__dirname, '../../../..');
const openapi = readFileSync(resolve(root, 'contracts/p2-leave/openapi.yaml'), 'utf8');
const errorCodes = readFileSync(resolve(root, 'contracts/p2-leave/error-codes.md'), 'utf8');

function operation(path: string): string {
  const start = openapi.indexOf(`\n  ${path}:\n`);
  expect(start, path).toBeGreaterThan(-1);
  const rest = openapi.slice(start + 1);
  const next = rest.search(/\n {2}\/api\//);
  return next === -1 ? rest : rest.slice(0, next);
}

describe('contracts/p2-leave frozen PKG_LEAVE side effects', () => {
  it('reject notification body matches PKG_LEAVE.pkb:309 (no date range)', () => {
    const reject = operation('/api/leave/requests/{id}/reject');
    expect(reject).toContain(
      'subject `Leave Request Rejected`, body\n        `Your leave request has been rejected. Reason: {comments}`',
    );
    expect(reject).not.toMatch(/to \{MM\/DD\/YYYY\} has been rejected/);
  });

  it('approve notification body keeps the legacy date range (PKG_LEAVE.pkb:255-258)', () => {
    expect(operation('/api/leave/requests/{id}/approve')).toContain(
      '`Your leave request from {MM/DD/YYYY} to {MM/DD/YYYY} has been approved.`',
    );
  });

  it.each([
    ['/api/leave/requests/{id}/cancel', '{old}->CANCELLED'],
    ['/api/leave/requests/{id}/approve', 'PENDING->APPROVED'],
    ['/api/leave/requests/{id}/reject', 'PENDING->REJECTED'],
  ])('%s writes UPDATE then STATUS_CHANGE audit rows', (path, transition) => {
    const op = operation(path);
    expect(op).toContain('`x-audit` (two rows, in this order)');
    expect(op).toContain('`UPDATE LEAVE_REQUESTS {id}` (old/new `null`)');
    expect(op).toContain(`\`STATUS_CHANGE LEAVE_REQUESTS {id} {"status":"${transition.split('->')[0]}"}->{"status":"${transition.split('->')[1]}"}\``);
  });

  it('submit writes a single INSERT audit row', () => {
    const submit = operation('/api/leave/requests');
    expect(submit).toContain('`x-audit`: `INSERT LEAVE_REQUESTS {requestId}`');
    expect(submit).not.toContain('STATUS_CHANGE');
  });

  it('carryover amount is the pre-pending remaining balance (PKG_LEAVE.pkb:567-572)', () => {
    const carryover = operation('/api/leave/admin/carryover/run');
    expect(carryover).toContain(
      '`remaining = openingBalance + accrued - used + adjustment` (**without** `- pending`',
    );
    expect(carryover).toContain('`carryoverFromPrev = openingBalance = LEAST(remaining, carryoverMax)`');
    expect(carryover).not.toContain('LEAST(available, carryoverMax)');
  });

  it('declares the leave_accrual_log CARRYOVER/EXPIRY rows as the only non-BUG technical exception', () => {
    expect(openapi).toContain('`LOG-01`');
    expect(errorCodes).toContain('| LOG-01 |');
    expect(errorCodes).toContain('| BUG-04 |');
    expect(errorCodes).toContain('| BUG-05 |');
    expect(errorCodes).toContain('| BUG-06 |');
  });
});
