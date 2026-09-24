import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/client';
import type { SystemParameter, SystemParameterRequest, SystemParameterUpdateRequest } from '../../api/types';
import { useAuth } from '../../app/AuthContext';
import { useErrorHandler } from '../../app/useErrorHandler';
import type { DtoName } from '../../validation/schema';
import { SchemaField, emptyValues, humanizeCode, parseWith, type FieldConfig, type FormValues } from '../shared/schemaForm';

const CREATE_FIELDS: FieldConfig[] = [
  { name: 'paramGroup', label: 'Group' },
  { name: 'paramCode', label: 'Code' },
  { name: 'dataType', label: 'Data type' },
  { name: 'paramValue', label: 'Value', hint: 'Must parse as the selected data type (server rule -20603)' },
  { name: 'paramDescription', label: 'Description' },
  { name: 'editableFlag', label: 'Editable' },
];
const UPDATE_FIELDS: FieldConfig[] = [
  { name: 'paramValue', label: 'Value', hint: 'Must parse as the parameter data type (server rule -20603)' },
  { name: 'paramDescription', label: 'Description' },
];

export const systemParametersKey = (group: string) => ['admin', 'system-parameters', { group: group || undefined }] as const;

/**
 * `HRMS_ADMIN › SYSTEM_PARAMETERS` block. Only `editableFlag=true` rows accept PUT / DELETE
 * (`-20606`); DELETE here is the contract's explicit hard-delete exception – there is no
 * `activeFlag` on SYSTEM_PARAMETERS. Writes need `ADMIN:EDIT`.
 */
export function SystemParametersTab() {
  const { hasAuthority } = useAuth();
  const canEdit = hasAuthority('ADMIN:EDIT');
  const queryClient = useQueryClient();
  const { handleError } = useErrorHandler();
  const [group, setGroup] = useState('');
  const [dialog, setDialog] = useState<{ row: SystemParameter | null } | null>(null);
  const [confirm, setConfirm] = useState<SystemParameter | null>(null);

  const result = useQuery({ queryKey: systemParametersKey(group), queryFn: () => api.admin.listSystemParameters(group ? { group } : {}), placeholderData: (prev) => prev });
  const refresh = () => void queryClient.invalidateQueries({ queryKey: ['admin', 'system-parameters'] });

  const remove = useMutation({
    mutationFn: (row: SystemParameter) => api.admin.deleteSystemParameter(row.paramId),
    onSuccess: () => {
      setConfirm(null);
      refresh();
    },
    onError: (error) => {
      handleError(error);
      setConfirm(null);
    },
  });

  const groups = [...new Set((result.data ?? []).map((p) => p.paramGroup))].sort();

  return (
    <div data-testid="admin-system-parameters">
      <p className="muted">
        Rebuilds <code>HRMS_ADMIN › SYSTEM_PARAMETERS block</code>
      </p>
      <form className="toolbar" onSubmit={(e) => e.preventDefault()} aria-label="System parameter filters">
        <label htmlFor="paramGroupFilter">Group</label>
        <input id="paramGroupFilter" list="paramGroups" value={group} onChange={(e) => setGroup(e.target.value)} placeholder="All groups" />
        <datalist id="paramGroups">
          {groups.map((g) => (
            <option key={g} value={g} />
          ))}
        </datalist>
        {canEdit && (
          <button type="button" onClick={() => setDialog({ row: null })}>
            New parameter
          </button>
        )}
      </form>

      {result.isPending && <p role="status">Loading system parameters…</p>}
      {result.isError && (
        <p role="alert" className="field-error">
          Could not load system parameters.
        </p>
      )}
      {result.data && result.data.length === 0 && <p role="status">No system parameters found.</p>}
      {result.data && result.data.length > 0 && (
        <table className="grid" aria-label="System parameters">
          <thead>
            <tr>
              <th scope="col">Group</th>
              <th scope="col">Code</th>
              <th scope="col">Value</th>
              <th scope="col">Type</th>
              <th scope="col">Description</th>
              <th scope="col">Editable</th>
              <th scope="col">Modified</th>
              {canEdit && (
                <th scope="col">
                  <span className="sr-only">Actions</span>
                </th>
              )}
            </tr>
          </thead>
          <tbody>
            {result.data.map((p) => (
              <tr key={p.paramId}>
                <td>{p.paramGroup}</td>
                <td>{p.paramCode}</td>
                <td>
                  <code>{p.paramValue}</code>
                </td>
                <td>{humanizeCode(p.dataType)}</td>
                <td>{p.paramDescription ?? '—'}</td>
                <td>{p.editableFlag ? 'Yes' : 'Locked'}</td>
                <td>{p.modifiedDate ? `${p.modifiedDate} · ${p.modifiedBy ?? ''}` : `${p.createdDate} · ${p.createdBy}`}</td>
                {canEdit && (
                  <td className="actions">
                    <button type="button" disabled={!p.editableFlag} title={p.editableFlag ? undefined : 'Not editable (-20606)'} onClick={() => setDialog({ row: p })} aria-label={`Edit ${p.paramGroup}.${p.paramCode}`}>
                      Edit
                    </button>
                    <button type="button" disabled={!p.editableFlag} title={p.editableFlag ? undefined : 'Not editable (-20606)'} onClick={() => setConfirm(p)} aria-label={`Delete ${p.paramGroup}.${p.paramCode}`}>
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
        <ParameterDialog
          row={dialog.row}
          onClose={() => setDialog(null)}
          onSaved={() => {
            setDialog(null);
            refresh();
          }}
        />
      )}

      {confirm && (
        <div className="dialog-overlay">
          <div role="dialog" aria-modal="true" aria-labelledby="delete-param-title" className="dialog">
            <h3 id="delete-param-title">
              Delete {confirm.paramGroup}.{confirm.paramCode}?
            </h3>
            <p>System parameters are removed permanently (no soft delete).</p>
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

function ParameterDialog({ row, onClose, onSaved }: { row: SystemParameter | null; onClose: () => void; onSaved: (row: SystemParameter) => void }) {
  const { handleError } = useErrorHandler();
  const dto: DtoName = row ? 'SystemParameterUpdateRequest' : 'SystemParameterRequest';
  const fields = row ? UPDATE_FIELDS : CREATE_FIELDS;
  const [values, setValues] = useState<FormValues>(() => emptyValues(dto, row ? { ...row } : { editableFlag: true }));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const parsed = parseWith<SystemParameterRequest & SystemParameterUpdateRequest>(dto, values);
    setErrors(parsed.errors);
    if (!parsed.data) return;
    setBusy(true);
    try {
      const saved = row ? await api.admin.updateSystemParameter(row.paramId, parsed.data) : await api.admin.createSystemParameter(parsed.data);
      onSaved(saved);
    } catch (error) {
      setErrors(handleError(error, { fieldNames: fields.map((f) => f.name) }).fieldErrors);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="dialog-overlay">
      <div role="dialog" aria-modal="true" aria-labelledby="param-dialog-title" className="dialog">
        <h3 id="param-dialog-title">{row ? `Edit ${row.paramGroup}.${row.paramCode} (${humanizeCode(row.dataType)})` : 'New system parameter'}</h3>
        <form onSubmit={submit} noValidate>
          {fields.map((f) => (
            <SchemaField key={f.name} dto={dto} field={f} values={values} errors={errors} onChange={(name, v) => setValues((prev) => ({ ...prev, [name]: v }))} idPrefix="param-" />
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
