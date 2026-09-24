import type { EmployeeListQuery, Money } from '../../api/types';
import { useAuth } from '../../app/AuthContext';
import { useModuleWritable } from '../../app/ModuleFlagsContext';
import { getDto, type DtoName } from '../../validation/schema';

export const employeeListKey = (params: EmployeeListQuery) => ['employees', 'list', params] as const;
export const employeeDetailKey = (id: number) => ['employees', 'detail', id] as const;
export const employeeHistoryKey = (id: number) => ['employees', 'history', id] as const;
export const employeeDependentsKey = (id: number) => ['employees', 'dependents', id] as const;
export const employeeContactsKey = (id: number) => ['employees', 'contacts', id] as const;
export const salaryCurrentKey = (id: number) => ['salary', 'current', id] as const;
export const salaryHistoryKey = (id: number) => ['salary', 'history', id] as const;

/**
 * Write controls exist only while the proxy reports `employee=NEW` (CUTOVER_PLAN.md §7.1);
 * at `NEW_READONLY` the page is read-only regardless of the caller's authorities.
 */
export function useEmployeeWrite() {
  const writable = useModuleWritable('employee');
  const { user, hasAuthority } = useAuth();
  return {
    readOnly: !writable,
    canEditEmployee: writable && hasAuthority('EMPLOYEE:EDIT'),
    canChangeSalary: writable && hasAuthority('PAYROLL:EDIT'),
    canReadSalary: (empId: number) => user?.empId === empId || hasAuthority('PAYROLL:VIEW') || hasAuthority('EMPLOYEE:EDIT'),
    canReadRelated: (empId: number) => user?.empId === empId || hasAuthority('EMPLOYEE:EDIT'),
    canEditRelated: (empId: number) => writable && (user?.empId === empId || hasAuthority('EMPLOYEE:EDIT')),
  };
}

/**
 * Drop empty strings so optional fields are omitted from the wire body (never sent as "").
 * With a DTO name, required text/date fields keep "" so the generated `required` message applies.
 */
export function compact<T extends Record<string, unknown>>(values: T, dto?: DtoName): Partial<T> {
  const fields = dto ? getDto(dto).fields : undefined;
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(values)) {
    if (v === undefined || v === null) continue;
    if (v === '') {
      const spec = fields?.[k];
      if (!spec?.required || (spec.type !== 'string' && spec.type !== 'date')) continue;
    }
    out[k] = v;
  }
  return out as Partial<T>;
}

/** Money travels as a two-decimal string (`NUMBER(12,2)`), never a float. */
export function toMoney(input: string): Money {
  return Number(input.replace(/,/g, '')).toFixed(2);
}

export function isOutOfGradeBand(amount: string, min: Money | null | undefined, max: Money | null | undefined): boolean {
  const n = Number(amount.replace(/,/g, ''));
  if (!Number.isFinite(n) || n <= 0) return false;
  if (min !== null && min !== undefined && n < Number(min)) return true;
  if (max !== null && max !== undefined && n > Number(max)) return true;
  return false;
}

export const EMPLOYMENT_TYPES = ['FULL_TIME', 'PART_TIME', 'CONTRACT', 'INTERN'] as const;
export const GENDERS = [
  { value: 'M', label: 'Male' },
  { value: 'F', label: 'Female' },
  { value: 'O', label: 'Other' },
] as const;
export const RELATIONSHIPS = ['SPOUSE', 'CHILD', 'PARENT', 'DOMESTIC_PARTNER', 'OTHER'] as const;
export const PAY_FREQUENCIES = ['WEEKLY', 'BIWEEKLY', 'SEMIMONTHLY', 'MONTHLY'] as const;
export const SALARY_BASES = ['ANNUAL', 'HOURLY'] as const;

export function humanize(code: string | null | undefined): string {
  if (!code) return '—';
  return code.charAt(0) + code.slice(1).toLowerCase().replace(/_/g, ' ');
}
