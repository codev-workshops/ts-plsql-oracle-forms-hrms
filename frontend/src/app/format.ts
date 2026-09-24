/**
 * Display masks from HRMS_COMMON_LIB.pll `format_date` (MM/DD/YYYY) and
 * `format_datetime` (MM/DD/YYYY HH24:MI). The wire format is ISO-8601.
 */

const pad = (n: number) => String(n).padStart(2, '0');

function parse(value: string | Date | null | undefined): Date | null {
  if (value === null || value === undefined || value === '') return null;
  if (value instanceof Date) return Number.isNaN(value.getTime()) ? null : value;
  // Date-only ISO strings are calendar dates: avoid the UTC shift of `new Date('YYYY-MM-DD')`.
  const dateOnly = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (dateOnly) return new Date(Number(dateOnly[1]), Number(dateOnly[2]) - 1, Number(dateOnly[3]));
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? null : d;
}

export function formatDate(value: string | Date | null | undefined): string {
  const d = parse(value);
  if (!d) return '';
  return `${pad(d.getMonth() + 1)}/${pad(d.getDate())}/${d.getFullYear()}`;
}

export function formatDateTime(value: string | Date | null | undefined): string {
  const d = parse(value);
  if (!d) return '';
  return `${formatDate(d)} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** Parse a user-typed MM/DD/YYYY into an ISO `YYYY-MM-DD` string, or null if invalid. */
export function parseDateMask(input: string): string | null {
  const m = /^(\d{2})\/(\d{2})\/(\d{4})$/.exec(input.trim());
  if (!m) return null;
  const [, mm, dd, yyyy] = m;
  const d = new Date(Number(yyyy), Number(mm) - 1, Number(dd));
  if (d.getFullYear() !== Number(yyyy) || d.getMonth() !== Number(mm) - 1 || d.getDate() !== Number(dd)) return null;
  return `${yyyy}-${mm}-${dd}`;
}

/** Money on the wire is a 2-dp decimal string; render with thousands separators. */
export function formatMoney(value: string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '';
  const [int, frac = '00'] = value.split('.');
  return `${Number(int).toLocaleString('en-US')}.${frac.padEnd(2, '0').slice(0, 2)}`;
}
