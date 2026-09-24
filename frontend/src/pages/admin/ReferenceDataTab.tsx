import { useState, type FormEvent, type ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '../../app/AuthContext';
import { useErrorHandler } from '../../app/useErrorHandler';
import type { DtoName } from '../../validation/schema';
import { SchemaField, emptyValues, parseWith, serializeDecimals, type FieldConfig, type FormValues } from '../shared/schemaForm';

export interface ReferenceColumn<Row> {
  key: string;
  label: string;
  render: (row: Row) => ReactNode;
}

export interface ReferenceDataConfig<Row extends { activeFlag: boolean }, Request extends object, Id extends string | number> {
  id: string;
  title: string;
  /** Legacy `HRMS_ADMIN` block / table this grid rebuilds (COMPONENT_MAPPING.md §9). */
  legacy: string;
  dto: DtoName;
  /** Natural key field – immutable once created (`-20601` on the server). */
  codeField: string;
  fields: FieldConfig[];
  columns: ReferenceColumn<Row>[];
  rowId: (row: Row) => Id;
  rowLabel: (row: Row) => string;
  list: (params: { active?: boolean }) => Promise<Row[]>;
  create: (body: Request) => Promise<Row>;
  update: (id: Id, body: Request) => Promise<Row>;
  deactivate: (id: Id) => Promise<void>;
  /** Query keys to invalidate besides this grid (the P0 read-only reference lists). */
  invalidates?: readonly (readonly unknown[])[];
  /** Server-side read-only rows (reserved pay elements, locked tax years): returns the reason, or null. */
  rowLocked?: (row: Row) => string | null;
  /** Rows that may be edited but never deactivated (reserved pay elements, `-20607`). */
  canDeactivate?: (row: Row) => boolean;
}

export const adminListKey = (id: string, params: { active?: boolean }) => ['admin', id, params] as const;

/**
 * One `HRMS_ADMIN` reference-data block, rebuilt from the frozen contract: list (`active` filter),
 * create / edit dialog validated by the exported `…Request` DTO, and DELETE = soft deactivation
 * (`activeFlag=false`, refused with `-20602` while dependants are active). Writes need `ADMIN:EDIT`.
 */
export function ReferenceDataTab<Row extends { activeFlag: boolean }, Request extends object, Id extends string | number>({ config, children }: { config: ReferenceDataConfig<Row, Request, Id>; children?: ReactNode }) {
  const { hasAuthority } = useAuth();
  const canEdit = hasAuthority('ADMIN:EDIT');
  const queryClient = useQueryClient();
  const { handleError } = useErrorHandler();
  const [includeInactive, setIncludeInactive] = useState(false);
  const [editing, setEditing] = useState<{ row: Row | null } | null>(null);
  const [confirm, setConfirm] = useState<Row | null>(null);

  const params = includeInactive ? {} : { active: true };
  const result = useQuery({ queryKey: adminListKey(config.id, params), queryFn: () => config.list(params) });

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['admin', config.id] });
    for (const key of config.invalidates ?? []) void queryClient.invalidateQueries({ queryKey: key });
  };

  const deactivate = useMutation({
    mutationFn: (row: Row) => config.deactivate(config.rowId(row)),
    onSuccess: () => {
      setConfirm(null);
      refresh();
    },
    onError: (error) => {
      handleError(error);
      setConfirm(null);
    },
  });

  return (
    <div data-testid={`admin-${config.id}`}>
      <p className="muted">
        Rebuilds <code>{config.legacy}</code>
      </p>
      <form className="toolbar" onSubmit={(e) => e.preventDefault()} aria-label={`${config.title} filters`}>
        <label>
          <input type="checkbox" checked={includeInactive} onChange={(e) => setIncludeInactive(e.target.checked)} /> Include inactive
        </label>
        {canEdit && (
          <button type="button" onClick={() => setEditing({ row: null })}>
            New {config.title.toLowerCase().replace(/s$/, '')}
          </button>
        )}
      </form>
      {children}

      {result.isPending && <p role="status">Loading {config.title.toLowerCase()}…</p>}
      {result.isError && (
        <p role="alert" className="field-error">
          Could not load {config.title.toLowerCase()}.
        </p>
      )}
      {result.data && result.data.length === 0 && <p role="status">No {config.title.toLowerCase()} found.</p>}
      {result.data && result.data.length > 0 && (
        <table className="grid" aria-label={config.title}>
          <thead>
            <tr>
              {config.columns.map((c) => (
                <th key={c.key} scope="col">
                  {c.label}
                </th>
              ))}
              <th scope="col">Status</th>
              {canEdit && (
                <th scope="col">
                  <span className="sr-only">Actions</span>
                </th>
              )}
            </tr>
          </thead>
          <tbody>
            {result.data.map((row) => (
              <tr key={String(config.rowId(row))} data-inactive={row.activeFlag ? undefined : 'true'}>
                {config.columns.map((c) => (
                  <td key={c.key}>{c.render(row)}</td>
                ))}
                <td>
                  <span className={`badge badge-${row.activeFlag ? 'ACTIVE' : 'INACTIVE'}`}>{row.activeFlag ? 'Active' : 'Inactive'}</span>
                </td>
                {canEdit && (
                  <td className="actions">
                    {(() => {
                      const locked = config.rowLocked?.(row) ?? null;
                      if (locked) return <span className="muted">{locked}</span>;
                      return (
                        <>
                          <button type="button" onClick={() => setEditing({ row })} aria-label={`Edit ${config.rowLabel(row)}`}>
                            Edit
                          </button>
                          {row.activeFlag && (config.canDeactivate?.(row) ?? true) && (
                            <button type="button" onClick={() => setConfirm(row)} aria-label={`Deactivate ${config.rowLabel(row)}`}>
                              Deactivate
                            </button>
                          )}
                        </>
                      );
                    })()}
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {editing && (
        <ReferenceDialog
          config={config}
          row={editing.row}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            refresh();
          }}
        />
      )}

      {confirm && (
        <div className="dialog-overlay">
          <div role="dialog" aria-modal="true" aria-labelledby={`deactivate-${config.id}-title`} className="dialog">
            <h3 id={`deactivate-${config.id}-title`}>Deactivate {config.rowLabel(confirm)}?</h3>
            <p>The row is kept for history (soft delete). Active dependants block deactivation.</p>
            <div className="actions">
              <button type="button" onClick={() => deactivate.mutate(confirm)} disabled={deactivate.isPending}>
                Deactivate
              </button>
              <button type="button" onClick={() => setConfirm(null)}>
                Cancel
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

interface DialogProps<Row extends { activeFlag: boolean }, Request extends object, Id extends string | number> {
  config: ReferenceDataConfig<Row, Request, Id>;
  row: Row | null;
  onClose: () => void;
  onSaved: (row: Row) => void;
}

function ReferenceDialog<Row extends { activeFlag: boolean }, Request extends object, Id extends string | number>({ config, row, onClose, onSaved }: DialogProps<Row, Request, Id>) {
  const { handleError } = useErrorHandler();
  const [values, setValues] = useState<FormValues>(() => emptyValues(config.dto, row ? { ...row, activeFlag: row.activeFlag } : { activeFlag: true }));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const fieldNames = config.fields.map((f) => f.name);
  const titleId = `${config.id}-dialog-title`;

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const parsed = parseWith<Record<string, unknown>>(config.dto, values);
    setErrors(parsed.errors);
    if (!parsed.data) return;
    const body = serializeDecimals(config.dto, parsed.data) as Request;
    setBusy(true);
    try {
      const saved = row ? await config.update(config.rowId(row), body) : await config.create(body);
      onSaved(saved);
    } catch (error) {
      setErrors(handleError(error, { fieldNames }).fieldErrors);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="dialog-overlay">
      <div role="dialog" aria-modal="true" aria-labelledby={titleId} className="dialog dialog-wide">
        <h3 id={titleId}>{row ? `Edit ${config.rowLabel(row)}` : `New ${config.title.toLowerCase().replace(/s$/, '')}`}</h3>
        <form onSubmit={submit} noValidate>
          {config.fields.map((f) => (
            <SchemaField
              key={f.name}
              dto={config.dto}
              field={f.name === config.codeField && row ? { ...f, readOnly: true, hint: 'Code is immutable once created' } : f}
              values={values}
              errors={errors}
              onChange={(name, v) => setValues((prev) => ({ ...prev, [name]: v }))}
              idPrefix={`${config.id}-`}
            />
          ))}
          <div className="actions">
            <button type="submit" disabled={busy}>
              {row ? 'Save' : 'Create'}
            </button>
            <button type="button" onClick={onClose}>
              Cancel
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
