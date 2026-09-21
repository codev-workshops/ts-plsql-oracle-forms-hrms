import { formatDate, formatDateTime, formatMoney, parseDateMask } from '../format';

describe('format.ts (HRMS_COMMON_LIB masks)', () => {
  it('formats ISO dates as MM/DD/YYYY without a timezone shift', () => {
    expect(formatDate('2024-03-05')).toBe('03/05/2024');
    expect(formatDate(new Date(2024, 11, 25))).toBe('12/25/2024');
    expect(formatDate(null)).toBe('');
    expect(formatDate('garbage')).toBe('');
  });

  it('formats date-times as MM/DD/YYYY HH24:MI', () => {
    expect(formatDateTime(new Date(2024, 0, 9, 17, 5))).toBe('01/09/2024 17:05');
  });

  it('parses MM/DD/YYYY back to ISO and rejects impossible dates', () => {
    expect(parseDateMask('02/29/2024')).toBe('2024-02-29');
    expect(parseDateMask('02/30/2024')).toBeNull();
    expect(parseDateMask('2024-02-29')).toBeNull();
  });

  it('formats 2-dp decimal strings with thousands separators', () => {
    expect(formatMoney('120000.00')).toBe('120,000.00');
    expect(formatMoney('45.5')).toBe('45.50');
    expect(formatMoney(null)).toBe('');
  });
});
