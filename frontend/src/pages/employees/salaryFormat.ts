export function formatMoney(value: string | number, currency = 'USD'): string {
  const n = typeof value === 'number' ? value : Number(value);
  if (!Number.isFinite(n)) return String(value);
  try {
    return new Intl.NumberFormat('en-US', { style: 'currency', currency, maximumFractionDigits: 2 }).format(n);
  } catch {
    return `${currency} ${n.toFixed(2)}`;
  }
}
