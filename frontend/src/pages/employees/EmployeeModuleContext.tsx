import { createContext, useContext, type ReactNode } from 'react';
import { useAuth } from '../../app/AuthContext';
import { type ModuleFlagValue, isModuleWritable } from '../../app/modules';

/**
 * CUTOVER_PLAN.md §7.1: the page mounts at `employee=NEW_READONLY` (reads only) and turns on
 * its write controls only at `employee=NEW`. Authorities follow `x-preauthorize` in
 * contracts/p3-employee/openapi.yaml.
 */
export interface EmployeeModuleAccess {
  /** `employee=NEW` – every write control is rendered only when true. */
  writable: boolean;
  /** `EMPLOYEE:EDIT` – create/update/terminate/transfer. */
  canEdit: boolean;
  /** `PAYROLL:EDIT` – `POST /api/employees/{id}/salary`. */
  canChangeSalary: boolean;
  /** Salary read scope for an employee: `PAYROLL:VIEW`, `EMPLOYEE:EDIT` or self. */
  canViewSalary: (empId: number) => boolean;
  /** Dependents / contacts scope: `EMPLOYEE:EDIT` or self. */
  canViewSubResources: (empId: number) => boolean;
  currentEmpId: number | null;
}

const Ctx = createContext<EmployeeModuleAccess | null>(null);

export function EmployeeModuleProvider({ flags, children }: { flags: Record<string, ModuleFlagValue>; children: ReactNode }) {
  const { user } = useAuth();
  const roles = user?.roles ?? [];
  const has = (a: (typeof roles)[number]) => roles.includes(a);
  const currentEmpId = user?.empId ?? null;
  const canEdit = has('EMPLOYEE:EDIT');
  const value: EmployeeModuleAccess = {
    writable: isModuleWritable('employee', flags),
    canEdit,
    canChangeSalary: has('PAYROLL:EDIT'),
    canViewSalary: (empId) => has('PAYROLL:VIEW') || canEdit || empId === currentEmpId,
    canViewSubResources: (empId) => canEdit || empId === currentEmpId,
    currentEmpId,
  };
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useEmployeeModule(): EmployeeModuleAccess {
  const v = useContext(Ctx);
  if (!v) throw new Error('useEmployeeModule must be used inside EmployeePage');
  return v;
}
