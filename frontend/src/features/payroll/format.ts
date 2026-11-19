const usd = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' });

export function money(value: number) {
  return usd.format(value);
}

export function positiveId(value: string | null) {
  if (!value || !/^\d+$/.test(value)) return undefined;
  const id = Number(value);
  return Number.isSafeInteger(id) && id > 0 ? id : undefined;
}
