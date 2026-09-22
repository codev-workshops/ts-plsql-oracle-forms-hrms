import type { EmployeeListQuery } from '../../api/types';

export const employeeKeys = {
  all: ['employees'] as const,
  list: (q: EmployeeListQuery) => ['employees', 'list', q] as const,
  detail: (id: number) => ['employees', id] as const,
  history: (id: number) => ['employees', id, 'history'] as const,
  salary: (id: number) => ['employees', id, 'salary'] as const,
  salaryHistory: (id: number) => ['employees', id, 'salary', 'history'] as const,
  dependents: (id: number) => ['employees', id, 'dependents'] as const,
  contacts: (id: number) => ['employees', id, 'contacts'] as const,
};
