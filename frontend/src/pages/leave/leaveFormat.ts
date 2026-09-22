import type { HalfDayPeriod } from '../../api/types';

/** `NUMBER(5,2)` day counts: 0.5 for a half day, otherwise up to two decimals. */
export function formatDays(days: number): string {
  return Number.isInteger(days) ? String(days) : days.toFixed(2).replace(/0$/, '');
}

export function describeHalfDay(halfDay: boolean, period: HalfDayPeriod | null): string {
  return halfDay ? `Half day${period ? ` (${period})` : ''}` : 'Full day';
}

export function isoToday(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

export function addDaysIso(iso: string, days: number): string {
  const [y, m, d] = iso.split('-').map(Number);
  const date = new Date(y, m - 1, d + days);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}
