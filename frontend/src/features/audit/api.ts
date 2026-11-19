import { api } from '../../api/client';

export type AuditAction = 'INSERT' | 'UPDATE' | 'DELETE';

export interface AuditEntry {
  auditId: number;
  tableName: string;
  recordId: number;
  action: AuditAction;
  oldValues?: Record<string, unknown> | null;
  newValues?: Record<string, unknown> | null;
  changedBy: string;
  changedDate: string;
}

export interface AuditFilter {
  tableName?: string;
  recordId?: string;
  action?: AuditAction | '';
  changedBy?: string;
  from?: string;
  to?: string;
  limit?: string;
}

export interface BatchResult {
  operation: string;
  rowsAffected: number;
}

function query(filter: AuditFilter): string {
  const params = new URLSearchParams();
  Object.entries(filter).forEach(([key, value]) => {
    if (value !== undefined && value !== '') params.set(key, String(value).trim());
  });
  const text = params.toString();
  return text ? `?${text}` : '';
}

export const auditApi = {
  search: (filter: AuditFilter) => api<AuditEntry[]>(`/audit/entries${query(filter)}`),
  get: (auditId: number) => api<AuditEntry>(`/audit/entries/${auditId}`),
  trail: (tableName: string, recordId: string) =>
    api<AuditEntry[]>(`/audit/records/${encodeURIComponent(tableName.trim())}/${encodeURIComponent(recordId.trim())}`),
  purge: (retentionDays?: number) =>
    api<BatchResult>('/audit/purge', { method: 'POST', body: retentionDays ? { retentionDays } : {} }),
};
