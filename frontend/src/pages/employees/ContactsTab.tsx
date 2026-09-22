import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { api } from '../../api/client';
import type { EmergencyContact, EmergencyContactRequest, EmployeeDetail } from '../../api/types';
import { useToast } from '../../app/ToastContext';
import { useErrorHandler } from '../../app/useErrorHandler';
import { getDto } from '../../validation/schema';
import { Dialog } from './Dialog';
import { useEmployeeModule } from './EmployeeModuleContext';
import { isRequired, parseForm, type FormValues } from './formUtils';
import { employeeKeys } from './queryKeys';
import { TextField } from './TextField';

const DTO = 'EmergencyContactRequest';
const FIELDS = Object.keys(getDto(DTO).fields);

function initial(c: EmergencyContact | null): FormValues {
  return {
    contactName: c?.contactName ?? '',
    relationship: c?.relationship ?? '',
    phonePrimary: c?.phonePrimary ?? '',
    phoneSecondary: c?.phoneSecondary ?? '',
    email: c?.email ?? '',
    priorityOrder: c ? String(c.priorityOrder) : '1',
    active: c ? String(c.active) : 'true',
  };
}

/** `EMERGENCY_CONTACT` block → `POST`/`PUT /api/employees/{id}/contacts[/{contactId}]`. */
export function ContactDialog({ employee, contact, onClose, onDone }: { employee: EmployeeDetail; contact: EmergencyContact | null; onClose: () => void; onDone: () => void }) {
  const [form, setForm] = useState<FormValues>(() => initial(contact));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const { handleError } = useErrorHandler();
  const { push } = useToast();
  const set = (k: string) => (v: string) => setForm((o) => ({ ...o, [k]: v }));

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const parsed = parseForm<EmergencyContactRequest & { active?: string }>(DTO, form);
    if (!parsed.ok) {
      setErrors(parsed.errors);
      return;
    }
    setErrors({});
    setBusy(true);
    try {
      const body: EmergencyContactRequest = { ...parsed.data, active: contact ? form.active === 'true' : undefined };
      if (contact) await api.employees.updateEmergencyContact(employee.id, contact.contactId, body);
      else await api.employees.addEmergencyContact(employee.id, body);
      push({ kind: 'success', message: contact ? 'Contact updated' : 'Contact added' });
      onDone();
    } catch (error) {
      setErrors(handleError(error, { fieldNames: FIELDS }).fieldErrors);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog title={contact ? 'Edit emergency contact' : 'Add emergency contact'} onSubmit={submit} onClose={onClose} busy={busy} submitLabel="Save">
      <TextField id="ct-contactName" label="Contact name" value={form.contactName} onChange={set('contactName')} required={isRequired(DTO, 'contactName')} error={errors.contactName} />
      <TextField id="ct-relationship" label="Relationship" value={form.relationship} onChange={set('relationship')} error={errors.relationship} />
      <TextField id="ct-phonePrimary" label="Primary phone" type="tel" value={form.phonePrimary} onChange={set('phonePrimary')} required={isRequired(DTO, 'phonePrimary')} error={errors.phonePrimary} />
      <TextField id="ct-phoneSecondary" label="Secondary phone" type="tel" value={form.phoneSecondary} onChange={set('phoneSecondary')} error={errors.phoneSecondary} />
      <TextField id="ct-email" label="E-mail" type="email" value={form.email} onChange={set('email')} error={errors.email} />
      <TextField id="ct-priorityOrder" label="Priority" type="number" min="1" value={form.priorityOrder} onChange={set('priorityOrder')} error={errors.priorityOrder} />
      {contact && <TextField id="ct-active" label="Active" value={form.active} onChange={set('active')} options={['true', 'false']} />}
    </Dialog>
  );
}

export function ContactsTab({ employee }: { employee: EmployeeDetail }) {
  const qc = useQueryClient();
  const { writable, canViewSubResources } = useEmployeeModule();
  const [editing, setEditing] = useState<EmergencyContact | null | 'new'>(null);
  const scoped = canViewSubResources(employee.id);
  const list = useQuery({ queryKey: employeeKeys.contacts(employee.id), queryFn: () => api.employees.listEmergencyContacts(employee.id), enabled: scoped });
  if (!scoped) return <p role="note">Emergency contacts are visible to the employee and HR only.</p>;
  const canWrite = writable && employee.employmentStatus !== 'TERMINATED';
  const done = () => {
    setEditing(null);
    void qc.invalidateQueries({ queryKey: employeeKeys.contacts(employee.id) });
  };
  return (
    <section aria-labelledby="contacts-title">
      <div className="toolbar">
        <h3 id="contacts-title">Emergency contacts</h3>
        {canWrite && (
          <button type="button" onClick={() => setEditing('new')}>
            Add contact
          </button>
        )}
      </div>
      {list.isPending ? (
        <p role="status">Loading…</p>
      ) : list.isError ? (
        <p role="alert">Could not load emergency contacts.</p>
      ) : list.data.length ? (
        <table className="grid" aria-label="Emergency contacts">
          <thead>
            <tr>
              <th>Priority</th>
              <th>Name</th>
              <th>Relationship</th>
              <th>Primary phone</th>
              <th>Secondary phone</th>
              <th>E-mail</th>
              <th>Active</th>
              {canWrite && <th />}
            </tr>
          </thead>
          <tbody>
            {list.data.map((c) => (
              <tr key={c.contactId}>
                <td>{c.priorityOrder}</td>
                <td>{c.contactName}</td>
                <td>{c.relationship ?? '—'}</td>
                <td>{c.phonePrimary}</td>
                <td>{c.phoneSecondary ?? '—'}</td>
                <td>{c.email ?? '—'}</td>
                <td>{c.active ? 'Yes' : 'No'}</td>
                {canWrite && (
                  <td>
                    <button type="button" onClick={() => setEditing(c)} aria-label={`Edit contact ${c.contactName}`}>
                      Edit
                    </button>
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <p>No emergency contacts recorded.</p>
      )}
      {editing !== null && <ContactDialog employee={employee} contact={editing === 'new' ? null : editing} onClose={() => setEditing(null)} onDone={done} />}
    </section>
  );
}
