import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useId, useState } from 'react';
import { api } from '../api/client';
import type { DepartmentRef, EmployeeSummary, JobTitleRef, LeaveTypeRef, LocationRef } from '../api/types';

/**
 * Replaces the Forms record groups / LOVs (`RG_DEPARTMENTS`, `RG_JOB_TITLES`, `RG_LOCATIONS`,
 * `RG_LEAVE_TYPES`, `RG_MANAGERS`) – COMPONENT_MAPPING.md §3.3, §7 `refresh_lov`.
 * React Query key is ['reference', source, params] so `invalidateReference(source)` is the
 * `POPULATE_GROUP` equivalent.
 */

export type ReferenceSource = 'departments' | 'job-titles' | 'locations' | 'leave-types' | 'managers';

interface Option {
  value: string;
  label: string;
}

const STALE_MS = 5 * 60 * 1000; // matches Cache-Control: max-age=300

function toOptions(source: ReferenceSource, rows: unknown[]): Option[] {
  switch (source) {
    case 'departments':
      return (rows as DepartmentRef[]).map((r) => ({ value: String(r.deptId), label: `${r.deptCode} – ${r.deptName}` }));
    case 'job-titles':
      return (rows as JobTitleRef[]).map((r) => ({ value: String(r.jobId), label: `${r.jobTitle} (${r.gradeCode})` }));
    case 'locations':
      return (rows as LocationRef[]).map((r) => ({ value: r.locationCode, label: r.locationName }));
    case 'leave-types':
      return (rows as LeaveTypeRef[]).map((r) => ({ value: String(r.leaveTypeId), label: r.leaveTypeName }));
    case 'managers':
      return (rows as EmployeeSummary[]).map((r) => ({ value: String(r.id), label: r.jobTitle ? `${r.name} – ${r.jobTitle}` : r.name }));
  }
}

async function fetchSource(source: ReferenceSource, opts: { active: boolean; q?: string; excludeSelf?: boolean }): Promise<unknown[]> {
  switch (source) {
    case 'departments':
      return api.reference.listDepartments({ active: opts.active });
    case 'job-titles':
      return api.reference.listJobTitles({ active: opts.active });
    case 'locations':
      return api.reference.listLocations({ active: opts.active });
    case 'leave-types':
      return api.reference.listLeaveTypes({ active: opts.active });
    case 'managers': {
      const page = await api.employees.searchEmployees({
        status: 'ACTIVE',
        fields: 'id,name,jobTitle',
        q: opts.q || undefined,
        excludeSelf: opts.excludeSelf,
        size: 20,
      });
      return page.content;
    }
  }
}

export function referenceQueryKey(source: ReferenceSource, params: Record<string, unknown> = {}) {
  return ['reference', source, params] as const;
}

export function useInvalidateReference() {
  const qc = useQueryClient();
  return (source?: ReferenceSource) => qc.invalidateQueries({ queryKey: source ? ['reference', source] : ['reference'] });
}

export interface ReferenceDropdownProps {
  source: ReferenceSource;
  value: string | null;
  onChange: (value: string | null) => void;
  label: string;
  name?: string;
  /** `active=false` fetches inactive rows too (admin/history use). */
  includeInactive?: boolean;
  /** Managers LOV only: remove jwt.empId from the result. */
  excludeSelf?: boolean;
  required?: boolean;
  disabled?: boolean;
  error?: string;
  placeholder?: string;
}

export function ReferenceDropdown({
  source,
  value,
  onChange,
  label,
  name,
  includeInactive = false,
  excludeSelf = false,
  required = false,
  disabled = false,
  error,
  placeholder = '— Select —',
}: ReferenceDropdownProps) {
  const id = useId();
  const [search, setSearch] = useState('');
  const searchable = source === 'managers';
  const params = { active: !includeInactive, ...(searchable ? { q: search, excludeSelf } : {}) };

  const query = useQuery({
    queryKey: referenceQueryKey(source, params),
    queryFn: () => fetchSource(source, params),
    staleTime: STALE_MS,
    select: (rows) => toOptions(source, rows),
  });

  const describedBy = error ? `${id}-error` : query.isError ? `${id}-load-error` : undefined;

  return (
    <div className="field">
      <label htmlFor={id}>
        {label}
        {required && <span aria-hidden="true"> *</span>}
      </label>
      {searchable && (
        <input
          type="search"
          aria-label={`Search ${label}`}
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          maxLength={100}
          disabled={disabled}
        />
      )}
      <select
        id={id}
        name={name}
        value={value ?? ''}
        onChange={(e) => onChange(e.target.value === '' ? null : e.target.value)}
        disabled={disabled || query.isPending}
        required={required}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        aria-busy={query.isFetching}
      >
        <option value="">{query.isPending ? 'Loading…' : placeholder}</option>
        {(query.data ?? []).map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
      {query.isError && (
        <span id={`${id}-load-error`} role="alert" className="field-error">
          Could not load {label.toLowerCase()}.{' '}
          <button type="button" onClick={() => query.refetch()}>
            Retry
          </button>
        </span>
      )}
      {error && (
        <span id={`${id}-error`} role="alert" className="field-error">
          {error}
        </span>
      )}
    </div>
  );
}
