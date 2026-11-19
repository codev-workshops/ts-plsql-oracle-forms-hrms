// Pre-registered module routes + nav entries (shared; Phase 3 slices must not edit this file).
// Each slice replaces the page exported from its own features/<module>/index.tsx.
export interface ModuleEntry {
  path: string;
  label: string;
  minGrade: number;
}

export const MODULES: ModuleEntry[] = [
  { path: '/leave', label: 'Leave', minGrade: 0 },
  { path: '/employees', label: 'Employees', minGrade: 0 },
  { path: '/payroll', label: 'Payroll', minGrade: 5 },
  { path: '/performance', label: 'Performance', minGrade: 0 },
  { path: '/audit', label: 'Audit', minGrade: 8 },
  { path: '/reports', label: 'Reports', minGrade: 5 },
];
