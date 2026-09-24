import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/client';
import type { Authority, Role, RoleRequest } from '../../api/types';
import { useAuth } from '../../app/AuthContext';
import { useErrorHandler } from '../../app/useErrorHandler';
import { useToast } from '../../app/ToastContext';
import { SchemaField, emptyValues, parseWith, type FieldConfig, type FormValues } from '../shared/schemaForm';

const FIELDS: FieldConfig[] = [
  { name: 'roleCode', label: 'Code' },
  { name: 'roleName', label: 'Name' },
  { name: 'minGrade', label: 'Minimum grade' },
  { name: 'maxGrade', label: 'Maximum grade', hint: 'Must be ≥ minimum grade (server rule -20603)' },
];

export const rolesKey = ['admin', 'roles'] as const;
export const authoritiesKey = ['admin', 'authorities'] as const;

/**
 * Schema v1 does not encode arrays, so the multi-select is validated here: nonempty, distinct,
 * every value from `GET /api/admin/authorities`. The Java `RoleRequest` re-checks on the server.
 */
export function validateSelection<T>(selected: readonly T[], allowed: readonly T[], label: string): string | null {
  if (selected.length === 0) return `Select at least one ${label}`;
  if (new Set(selected).size !== selected.length) return `${label[0].toUpperCase()}${label.slice(1)}s must be distinct`;
  const bad = selected.find((s) => !allowed.includes(s));
  return bad === undefined ? null : `Unknown ${label}: ${String(bad)}`;
}

/**
 * Role administration (auth-owned, `/api/admin/roles`). Seeded roles 1–3 are read-only
 * (`-20802`); DELETE is a physical delete refused while assigned (`-20803`). Successful updates
 * report `sessionsRevoked` so the admin knows holders must sign in again.
 */
export function RolesTab() {
  const { hasAuthority } = useAuth();
  const canEdit = hasAuthority('ADMIN:EDIT');
  const queryClient = useQueryClient();
  const { handleError } = useErrorHandler();
  const { push } = useToast();
  const [dialog, setDialog] = useState<{ row: Role | null } | null>(null);
  const [confirm, setConfirm] = useState<Role | null>(null);

  const roles = useQuery({ queryKey: rolesKey, queryFn: () => api.admin.listRoles() });
  const authorities = useQuery({ queryKey: authoritiesKey, queryFn: () => api.admin.listAuthorities(), enabled: canEdit });
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: rolesKey });
    void queryClient.invalidateQueries({ queryKey: ['admin', 'users'] });
  };

  const remove = useMutation({
    mutationFn: (row: Role) => api.admin.deleteRole(row.roleId),
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
    <div data-testid="admin-roles">
      <p className="muted">
        Rebuilds <code>ROLES / ROLE_PERMISSIONS (RBAC grants; seeded roles read-only)</code>
      </p>
      <form className="toolbar" onSubmit={(e) => e.preventDefault()} aria-label="Role filters">
        {canEdit && (
          <button type="button" onClick={() => setDialog({ row: null })}>
            New role
          </button>
        )}
      </form>

      {roles.isPending && <p role="status">Loading roles…</p>}
      {roles.isError && (
        <p role="alert" className="field-error">
          Could not load roles.
        </p>
      )}
      {roles.data && roles.data.length > 0 && (
        <table className="grid" aria-label="Roles">
          <thead>
            <tr>
              <th scope="col">Code</th>
              <th scope="col">Name</th>
              <th scope="col">Grades</th>
              <th scope="col">Permissions</th>
              <th scope="col">Users</th>
              {canEdit && (
                <th scope="col">
                  <span className="sr-only">Actions</span>
                </th>
              )}
            </tr>
          </thead>
          <tbody>
            {roles.data.map((r) => (
              <tr key={r.roleId}>
                <td>{r.seeded ? `${r.roleCode} (seeded)` : r.roleCode}</td>
                <td>{r.roleName}</td>
                <td>
                  {r.minGrade}–{r.maxGrade}
                </td>
                <td>
                  <ul className="inline-list" aria-label={`Permissions of ${r.roleCode}`}>
                    {r.permissions.map((p) => (
                      <li key={p}>
                        <code>{p}</code>
                      </li>
                    ))}
                  </ul>
                </td>
                <td>{r.userCount}</td>
                {canEdit && (
                  <td className="actions">
                    <button type="button" disabled={r.seeded} title={r.seeded ? 'Seeded role (-20802)' : undefined} onClick={() => setDialog({ row: r })} aria-label={`Edit ${r.roleCode}`}>
                      Edit
                    </button>
                    <button type="button" disabled={r.seeded || r.userCount > 0} title={r.seeded ? 'Seeded role (-20802)' : r.userCount > 0 ? 'Assigned to users (-20803)' : undefined} onClick={() => setConfirm(r)} aria-label={`Delete ${r.roleCode}`}>
                      Delete
                    </button>
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {dialog && (
        <RoleDialog
          row={dialog.row}
          authorities={authorities.data ?? []}
          onClose={() => setDialog(null)}
          onSaved={(saved, sessionsRevoked) => {
            setDialog(null);
            if (sessionsRevoked !== undefined) push({ kind: 'success', message: `${saved.roleCode} saved · ${sessionsRevoked} session(s) revoked` });
            refresh();
          }}
        />
      )}

      {confirm && (
        <div className="dialog-overlay">
          <div role="dialog" aria-modal="true" aria-labelledby="delete-role-title" className="dialog">
            <h3 id="delete-role-title">Delete role {confirm.roleCode}?</h3>
            <p>Roles are removed permanently (no soft delete). Roles still assigned to users are refused.</p>
            <div className="actions">
              <button type="button" onClick={() => remove.mutate(confirm)} disabled={remove.isPending}>
                Delete
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

function RoleDialog({ row, authorities, onClose, onSaved }: { row: Role | null; authorities: Authority[]; onClose: () => void; onSaved: (row: Role, sessionsRevoked?: number) => void }) {
  const { handleError } = useErrorHandler();
  const [values, setValues] = useState<FormValues>(() => emptyValues('RoleRequest', row ? { ...row } : {}));
  const [permissions, setPermissions] = useState<Authority[]>(row?.permissions ?? []);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const parsed = parseWith<Omit<RoleRequest, 'permissions'>>('RoleRequest', values);
    const permissionError = validateSelection(permissions, authorities, 'permission');
    const nextErrors = { ...parsed.errors, ...(permissionError ? { permissions: permissionError } : {}) };
    setErrors(nextErrors);
    if (!parsed.data || permissionError) return;
    const body: RoleRequest = { ...parsed.data, permissions };
    setBusy(true);
    try {
      if (row) {
        const saved = await api.admin.updateRole(row.roleId, body);
        onSaved(saved, saved.sessionsRevoked);
      } else {
        onSaved(await api.admin.createRole(body));
      }
    } catch (error) {
      setErrors(handleError(error, { fieldNames: [...FIELDS.map((f) => f.name), 'permissions'] }).fieldErrors);
    } finally {
      setBusy(false);
    }
  };

  const toggle = (a: Authority, checked: boolean) => setPermissions((prev) => (checked ? [...new Set([...prev, a])] : prev.filter((p) => p !== a)));

  return (
    <div className="dialog-overlay">
      <div role="dialog" aria-modal="true" aria-labelledby="role-dialog-title" className="dialog dialog-wide">
        <h3 id="role-dialog-title">{row ? `Edit role ${row.roleCode}` : 'New role'}</h3>
        <form onSubmit={submit} noValidate>
          {FIELDS.map((f) => (
            <SchemaField key={f.name} dto="RoleRequest" field={f.name === 'roleCode' && row ? { ...f, readOnly: true, hint: 'Code is immutable once created' } : f} values={values} errors={errors} onChange={(name, v) => setValues((prev) => ({ ...prev, [name]: v }))} idPrefix="role-" />
          ))}
          <fieldset aria-describedby={errors.permissions ? 'role-permissions-error' : undefined} aria-invalid={errors.permissions ? true : undefined}>
            <legend>Permissions</legend>
            {authorities.map((a) => (
              <label key={a} className="checkbox">
                <input type="checkbox" checked={permissions.includes(a)} onChange={(e) => toggle(a, e.target.checked)} /> <code>{a}</code>
              </label>
            ))}
            {errors.permissions && (
              <p id="role-permissions-error" role="alert" className="field-error">
                {errors.permissions}
              </p>
            )}
          </fieldset>
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
