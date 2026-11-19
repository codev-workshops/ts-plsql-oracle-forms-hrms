import { useEffect, useState } from 'react';
import { ApiError } from '../../api/client';
import { employeeApi, type RefItem } from './api';

export function errorText(err: unknown): string {
  return err instanceof ApiError ? `${err.message} (${err.code})` : 'Unexpected error';
}

export interface RefData {
  departments: RefItem[];
  jobs: RefItem[];
  locations: RefItem[];
}

export function useRefData(): RefData {
  const [data, setData] = useState<RefData>({ departments: [], jobs: [], locations: [] });
  useEffect(() => {
    Promise.all([employeeApi.departments(), employeeApi.jobTitles(), employeeApi.locations()])
      .then(([departments, jobs, locations]) => setData({ departments, jobs, locations }))
      .catch(() => setData({ departments: [], jobs: [], locations: [] }));
  }, []);
  return data;
}

export function RefSelect({ label, items, value, onChange, required, emptyLabel = '—' }: {
  label: string; items: RefItem[]; value: string; onChange: (v: string) => void; required?: boolean;
  emptyLabel?: string;
}) {
  return (
    <label>
      {label}
      <select value={value} onChange={(e) => onChange(e.target.value)} required={required}>
        <option value="">{emptyLabel}</option>
        {items.map((i) => <option key={i.id} value={i.id}>{i.name}</option>)}
      </select>
    </label>
  );
}

export function optionalNumber(v: string): number | undefined {
  return v === '' ? undefined : Number(v);
}

export function money(v?: number | null): string {
  return v === undefined || v === null ? '—' : v.toLocaleString('en-US', { style: 'currency', currency: 'USD' });
}
