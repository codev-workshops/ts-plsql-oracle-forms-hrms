/** React Query keys for the leave module; `['leave']` invalidates everything after a mutation. */
export const leaveKeys = {
  mine: ['leave', 'requests', 'mine'] as const,
  balances: ['leave', 'balances', 'mine'] as const,
  pending: ['leave', 'approvals', 'pending'] as const,
  businessDays: (start: string, end: string) => ['leave', 'business-days', start, end] as const,
  calendar: (from: string, to: string) => ['leave', 'team-calendar', from, to] as const,
};

export function formatDays(totalDays: number, halfDay: boolean, period: 'AM' | 'PM' | null) {
  return halfDay ? `½ (${period ?? '?'})` : String(totalDays);
}
