import { useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/client';
import type { AuditLogSearchQuery } from '../../api/types';
import { normaliseError } from '../../app/useErrorHandler';
import { SchemaField, emptyValues, parseWith, type FieldConfig, type FormValues } from '../shared/schemaForm';

const FIELDS: FieldConfig[] = [
  { name: 'tableName', label: 'Table' },
  { name: 'recordId', label: 'Record id' },
  { name: 'actionType', label: 'Action' },
  { name: 'changedBy', label: 'Changed by' },
  { name: 'from', label: 'From' },
  { name: 'to', label: 'To' },
];
const PAGE_SIZE = 50;

export const auditLogKey = (query: AuditLogSearchQuery) => ['admin', 'audit-log', query] as const;

/**
 * `HRMS_ADMIN › AUDIT_LOG` block → `GET /api/admin/audit-log` (read-only; the log is written by
 * the owning modules, never by this page). `from`/`to` order is the `audit.range` rule of the
 * exported `AuditLogSearchQuery`. Needs `ADMIN:VIEW`.
 */
export function AuditLogTab() {
  const [values, setValues] = useState<FormValues>(() => emptyValues('AuditLogSearchQuery'));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [query, setQuery] = useState<AuditLogSearchQuery>({});
  const [page, setPage] = useState(0);
  const [expanded, setExpanded] = useState<number | null>(null);

  const result = useQuery({
    queryKey: auditLogKey({ ...query, page }),
    queryFn: () => api.admin.searchAuditLog({ ...query, page, size: PAGE_SIZE }),
    placeholderData: (prev) => prev,
  });

  const search = (event: FormEvent) => {
    event.preventDefault();
    const parsed = parseWith<AuditLogSearchQuery>('AuditLogSearchQuery', values);
    setErrors(parsed.errors);
    if (!parsed.data) return;
    setPage(0);
    setQuery(parsed.data);
  };

  const data = result.data;
  const serverError = result.isError ? normaliseError(result.error).apiError : null;

  return (
    <div data-testid="admin-audit-log">
      <p className="muted">
        Rebuilds <code>HRMS_ADMIN › AUDIT_LOG block</code>
      </p>
      <form className="toolbar filters" onSubmit={search} noValidate aria-label="Audit log filters">
        {FIELDS.map((f) => (
          <SchemaField key={f.name} dto="AuditLogSearchQuery" field={f} values={values} errors={errors} onChange={(n, v) => setValues((p) => ({ ...p, [n]: v }))} idPrefix="audit-" />
        ))}
        <div className="actions">
          <button type="submit">Search</button>
        </div>
      </form>

      {result.isPending && <p role="status">Loading audit log…</p>}
      {result.isError && (
        <p role="alert" className="field-error">
          Could not load the audit log.{serverError ? ` ${serverError.code}: ${serverError.message}` : ''}
        </p>
      )}
      {data && data.content.length === 0 && <p role="status">No audit entries match the selected filters.</p>}
      {data && data.content.length > 0 && (
        <>
          <table className="grid" aria-label="Audit log">
            <thead>
              <tr>
                <th scope="col">Id</th>
                <th scope="col">Table</th>
                <th scope="col">Record</th>
                <th scope="col">Action</th>
                <th scope="col">Changed by</th>
                <th scope="col">Changed at</th>
                <th scope="col">IP</th>
                <th scope="col">
                  <span className="sr-only">Values</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {data.content.map((r) => (
                <tr key={r.auditId}>
                  <td>{r.auditId}</td>
                  <td>{r.tableName}</td>
                  <td>{r.recordId}</td>
                  <td>
                    <span className={`badge badge-${r.actionType}`}>{r.actionType}</span>
                  </td>
                  <td>{r.changedBy}</td>
                  <td>{r.changedDate}</td>
                  <td>{r.ipAddress ?? '—'}</td>
                  <td>
                    <button type="button" className="link" aria-expanded={expanded === r.auditId} onClick={() => setExpanded(expanded === r.auditId ? null : r.auditId)}>
                      {expanded === r.auditId ? 'Hide values' : 'Show values'}
                    </button>
                    {expanded === r.auditId && (
                      <dl className="kv" data-testid={`audit-values-${r.auditId}`}>
                        <dt>Old</dt>
                        <dd>
                          <pre>{r.oldValues ?? '—'}</pre>
                        </dd>
                        <dt>New</dt>
                        <dd>
                          <pre>{r.newValues ?? '—'}</pre>
                        </dd>
                      </dl>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="toolbar-pagination">
            <button type="button" onClick={() => setPage((p) => Math.max(0, p - 1))} disabled={data.page.page === 0}>
              Previous
            </button>
            <span>
              Page {data.page.page + 1} of {Math.max(1, data.page.totalPages)} · {data.page.totalElements} entries
            </span>
            <button type="button" onClick={() => setPage((p) => p + 1)} disabled={data.page.page + 1 >= data.page.totalPages}>
              Next
            </button>
          </div>
        </>
      )}
    </div>
  );
}
