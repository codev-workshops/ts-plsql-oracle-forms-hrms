import type { ReactElement } from 'react';
import { setAccessToken } from '../../../api/http';
import { MOCK_USERS, seedAccessToken } from '../../../mocks/handlers';
import { renderWithProviders } from '../../../test/render';

export function renderLeaveAs(email: string, ui: ReactElement, path = '/leave') {
  setAccessToken(seedAccessToken(email));
  return renderWithProviders(ui, { initialUser: MOCK_USERS[email], initialEntries: [path] });
}

export function iso(offsetDays: number, from = new Date()): string {
  const d = new Date(from.getFullYear(), from.getMonth(), from.getDate() + offsetDays);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/** Next weekday at or after `offsetDays` from today (msw business-day rules exclude weekends). */
export function weekday(offsetDays: number): string {
  let d = offsetDays;
  for (;;) {
    const date = new Date(iso(d) + 'T00:00:00');
    if (date.getDay() !== 0 && date.getDay() !== 6) return iso(d);
    d++;
  }
}
