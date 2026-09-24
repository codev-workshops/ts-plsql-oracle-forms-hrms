import { useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/client';
import type { AccountStatus, Role, UserAccount, UserStatusRequest } from '../../api/types';
import { useAuth } from '../../app/AuthContext';
import { useErrorHandler } from '../../app/useErrorHandler';
import { useToast } from '../../app/ToastContext';
import { SchemaField, emptyValues, parseWith, type FieldConfig, type FormValues } from '../shared/schemaForm';
import { rolesKey, validateSelection } from './RolesTab';

const STATUS_FIELDS: FieldConfig[] = [
  { name: 'status', label: 'Status' },
  { name: 'reason', label: 'Reason', hint: 'Recorded in the audit log' },
];

export const USERS_PAGE_SIZE = 20;

export const usersKey = (params: { q?: string; status?: AccountStatus; page?: number; size?: number }) => ['admin', 'users', params] as const;

/**
 * User-account administration (`/api/admin/users`): role assignment (schema v1 carries no array
 * rules, so `roleIds` is validated here – nonempty, distinct, existing roles) and ACTIVE/DISABLED
 * status. Effective authorities are the union of the assigned roles' permissions as computed by
 * the server. Self-modification (`-20804`) and last-admin (`-20805`) rules stay server-side; the
 * audit actor is the JWT `sub`, never sent in the request body.
 */
export function UsersTab() {
  const { user: me, hasAuthority } = useAuth();
  const canEdit = hasAuthority('ADMIN:EDIT');
  const queryClient = useQueryClient();
  const { push } = useToast();
  const [q, setQ] = useState('');
  const [status, setStatus] = useState<'' | AccountStatus>('');
  const [page, setPage] = useState(0);
  const [rolesFor, setRolesFor] = useState<UserAccount | null>(null);
  const [statusFor, setStatusFor] = useState<UserAccount | null>(null);

  const params = { ...(q ? { q } : {}), ...(status ? { status } : {}), page, size: USERS_PAGE_SIZE };
  const users = useQuery({ queryKey: usersKey(params), queryFn: () => api.admin.searchUsers(params), placeholderData: (prev) => prev });
  const roles = useQuery({ queryKey: rolesKey, queryFn: () => api.admin.listRoles(), enabled: canEdit });
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['admin', 'users'] });
    void queryClient.invalidateQueries({ queryKey: rolesKey });
  };
  const saved = (account: UserAccount, sessionsRevoked: number) => {
    setRolesFor(null);
    setStatusFor(null);
    push({ kind: 'success', message: `${account.username} saved · ${sessionsRevoked} session(s) revoked` });
    refresh();
  };

  return (
    <div data-testid="admin-users">
      <p className="muted">
        Rebuilds <code>USER_ACCOUNTS / USER_ROLES (grants; effective authority = union of role permissions)</code>
      </p>
      <form className="toolbar" onSubmit={(e) => e.preventDefault()} aria-label="User filters">
        <label htmlFor="userSearch">Search</label>
        <input id="userSearch" value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }} placeholder="Username, employee number or name" />
        <label htmlFor="userStatus">Status</label>
        <select id="userStatus" value={status} onChange={(e) => { setStatus(e.target.value as '' | AccountStatus); setPage(0); }}>
          <option value="">All</option>
          <option value="ACTIVE">Active</option>
          <option value="DISABLED">Disabled</option>
        </select>
      </form>

      {users.isPending && <p role="status">Loading user accounts…</p>}
      {users.isError && (
        <p role="alert" className="field-error">
          Could not load user accounts.
        </p>
      )}
      {users.data && users.data.content.length === 0 && <p role="status">No user accounts found.</p>}
      {users.data && users.data.content.length > 0 && (
        <>
        <table className="grid" aria-label="User accounts">
          <thead>
            <tr>
              <th scope="col">Username</th>
              <th scope="col">Employee</th>
              <th scope="col">Status</th>
              <th scope="col">Roles</th>
              <th scope="col">Effective authorities</th>
              {canEdit && (
                <th scope="col">
                  <span className="sr-only">Actions</span>
                </th>
              )}
            </tr>
          </thead>
          <tbody>
            {users.data.content.map((u) => {
              const self = me?.userId === String(u.userId) || me?.empId === u.empId;
              return (
                <tr key={u.userId}>
                  <td>{u.username}</td>
                  <td>
                    {u.fullName} <span className="muted">({u.empNumber})</span>
                  </td>
                  <td>
                    <span className={`badge badge-${u.status}`}>{u.status === 'ACTIVE' ? 'Active' : 'Disabled'}</span>
                    {u.locked && <span className="badge badge-LOCKED"> Locked</span>}
                  </td>
                  <td>{u.roles.map((g) => g.roleCode).join(', ') || '—'}</td>
                  <td>
                    <ul className="inline-list" aria-label={`Effective authorities of ${u.username}`}>
                      {u.authorities.map((a) => (
                        <li key={a}>
                          <code>{a}</code>
                        </li>
                      ))}
                    </ul>
                  </td>
                  {canEdit && (
                    <td className="actions">
                      <button type="button" disabled={self} title={self ? 'Cannot modify your own account (-20804)' : undefined} onClick={() => setRolesFor(u)} aria-label={`Assign roles to ${u.username}`}>
                        Roles
                      </button>
                      <button type="button" disabled={self} title={self ? 'Cannot modify your own account (-20804)' : undefined} onClick={() => setStatusFor(u)} aria-label={`Change status of ${u.username}`}>
                        Status
                      </button>
                    </td>
                  )}
                </tr>
              );
            })}
          </tbody>
        </table>
        <div className="toolbar-pagination">
          <button type="button" onClick={() => setPage((p) => Math.max(0, p - 1))} disabled={users.data.page.page === 0}>
            Previous
          </button>
          <span>
            Page {users.data.page.page + 1} of {Math.max(1, users.data.page.totalPages)} · {users.data.page.totalElements} accounts
          </span>
          <button type="button" onClick={() => setPage((p) => p + 1)} disabled={users.data.page.page + 1 >= users.data.page.totalPages}>
            Next
          </button>
        </div>
        </>
      )}

      {rolesFor && <RolesDialog account={rolesFor} roles={roles.data ?? []} onClose={() => setRolesFor(null)} onSaved={saved} />}
      {statusFor && <StatusDialog account={statusFor} onClose={() => setStatusFor(null)} onSaved={saved} />}
    </div>
  );
}

function RolesDialog({ account, roles, onClose, onSaved }: { account: UserAccount; roles: Role[]; onClose: () => void; onSaved: (account: UserAccount, sessionsRevoked: number) => void }) {
  const { handleError } = useErrorHandler();
  const [roleIds, setRoleIds] = useState<number[]>(account.roles.map((g) => g.roleId));
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const preview = [...new Set(roles.filter((r) => roleIds.includes(r.roleId)).flatMap((r) => r.permissions))].sort();

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const invalid = validateSelection(
      roleIds,
      roles.map((r) => r.roleId),
      'role',
    );
    setError(invalid);
    if (invalid) return;
    setBusy(true);
    try {
      const result = await api.admin.replaceUserRoles(account.userId, { roleIds });
      onSaved(result, result.sessionsRevoked);
    } catch (e) {
      setError(handleError(e, { fieldNames: ['roleIds'] }).fieldErrors.roleIds ?? null);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="dialog-overlay">
      <div role="dialog" aria-modal="true" aria-labelledby="user-roles-title" className="dialog dialog-wide">
        <h3 id="user-roles-title">Roles for {account.username}</h3>
        <form onSubmit={submit} noValidate>
          <fieldset aria-describedby={error ? 'user-roles-error' : undefined} aria-invalid={error ? true : undefined}>
            <legend>Assigned roles</legend>
            {roles.map((r) => (
              <label key={r.roleId} className="checkbox">
                <input type="checkbox" checked={roleIds.includes(r.roleId)} onChange={(e) => setRoleIds((prev) => (e.target.checked ? [...new Set([...prev, r.roleId])] : prev.filter((id) => id !== r.roleId)))} /> {r.roleCode} <span className="muted">– {r.roleName}</span>
              </label>
            ))}
            {error && (
              <p id="user-roles-error" role="alert" className="field-error">
                {error}
              </p>
            )}
          </fieldset>
          <p className="muted" data-testid="authority-preview">
            Effective authorities after save: {preview.length ? preview.join(', ') : '—'}
          </p>
          <div className="actions">
            <button type="submit" disabled={busy}>
              Save
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

function StatusDialog({ account, onClose, onSaved }: { account: UserAccount; onClose: () => void; onSaved: (account: UserAccount, sessionsRevoked: number) => void }) {
  const { handleError } = useErrorHandler();
  const [values, setValues] = useState<FormValues>(() => emptyValues('UserStatusRequest', { status: account.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE' }));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const parsed = parseWith<UserStatusRequest>('UserStatusRequest', values);
    setErrors(parsed.errors);
    if (!parsed.data) return;
    setBusy(true);
    try {
      const result = await api.admin.setUserStatus(account.userId, parsed.data);
      onSaved(result, result.sessionsRevoked);
    } catch (e) {
      setErrors(handleError(e, { fieldNames: STATUS_FIELDS.map((f) => f.name) }).fieldErrors);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="dialog-overlay">
      <div role="dialog" aria-modal="true" aria-labelledby="user-status-title" className="dialog">
        <h3 id="user-status-title">Change status of {account.username}</h3>
        <form onSubmit={submit} noValidate>
          {STATUS_FIELDS.map((f) => (
            <SchemaField key={f.name} dto="UserStatusRequest" field={f} values={values} errors={errors} onChange={(name, v) => setValues((prev) => ({ ...prev, [name]: v }))} idPrefix="user-status-" />
          ))}
          <div className="actions">
            <button type="submit" disabled={busy}>
              Save
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
