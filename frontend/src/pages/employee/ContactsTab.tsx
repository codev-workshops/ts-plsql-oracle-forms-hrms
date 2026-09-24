import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { api } from '../../api/client';
import type { EmergencyContact, EmergencyContactRequest, EmployeeDetail } from '../../api/types';
import { useToast } from '../../app/ToastContext';
import { useErrorHandler } from '../../app/useErrorHandler';
import { fieldErrors as zodFieldErrors, zodFor } from '../../validation/schema';
import { compact, employeeContactsKey, useEmployeeWrite } from './employeeShared';

const schema = zodFor('EmergencyContactRequest');
const FIELDS = ['contactName', 'relationship', 'phonePrimary', 'phoneSecondary', 'email', 'priorityOrder', 'active'] as const;

interface Values {
  contactName: string;
  relationship: string;
  phonePrimary: string;
  phoneSecondary: string;
  email: string;
  priorityOrder: string;
  active: boolean;
}

const EMPTY: Values = { contactName: '', relationship: '', phonePrimary: '', phoneSecondary: '', email: '', priorityOrder: '1', active: true };

function fromRow(c: EmergencyContact): Values {
  return {
    contactName: c.contactName,
    relationship: c.relationship ?? '',
    phonePrimary: c.phonePrimary,
    phoneSecondary: c.phoneSecondary ?? '',
    email: c.email ?? '',
    priorityOrder: String(c.priorityOrder),
    active: c.active,
  };
}

/** `EMERGENCY_CONTACT` block (HRMS_EMPLOYEE.fmb "Emergency Contacts" tab) → `/api/employees/{id}/contacts`. */
export function ContactsTab({ employee }: { employee: EmployeeDetail }) {
  const qc = useQueryClient();
  const { canEditRelated } = useEmployeeWrite();
  const editable = canEditRelated(employee.id) && employee.employmentStatus !== 'TERMINATED';
  const [editing, setEditing] = useState<EmergencyContact | 'new' | null>(null);
  const contacts = useQuery({ queryKey: employeeContactsKey(employee.id), queryFn: () => api.employees.listEmergencyContacts(employee.id) });
  const saved = () => {
    setEditing(null);
    void qc.invalidateQueries({ queryKey: employeeContactsKey(employee.id) });
  };

  return (
    <section aria-labelledby="contacts-title">
      <div className="toolbar">
        <h3 id="contacts-title">Emergency contacts</h3>
        {editable && editing === null && <button type="button" onClick={() => setEditing('new')}>Add contact</button>}
      </div>
      {contacts.isPending ? (
        <p role="status">Loading…</p>
      ) : contacts.isError ? (
        <p role="alert">Could not load emergency contacts.</p>
      ) : contacts.data.length ? (
        <table className="grid" aria-label="Emergency contacts">
          <thead>
            <tr><th>Priority</th><th>Name</th><th>Relationship</th><th>Primary phone</th><th>Secondary phone</th><th>E-mail</th><th>Active</th>{editable && <th />}</tr>
          </thead>
          <tbody>
            {contacts.data.map((c) => (
              <tr key={c.contactId}>
                <td>{c.priorityOrder}</td>
                <td>{c.contactName}</td>
                <td>{c.relationship ?? '—'}</td>
                <td>{c.phonePrimary}</td>
                <td>{c.phoneSecondary ?? '—'}</td>
                <td>{c.email ?? '—'}</td>
                <td>{c.active ? 'Yes' : 'No'}</td>
                {editable && (
                  <td>
                    <button type="button" onClick={() => setEditing(c)} aria-label={`Edit contact ${c.contactName}`}>Edit</button>
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <p>No emergency contacts recorded.</p>
      )}
      {editing !== null && editable && (
        <ContactForm empId={employee.id} contact={editing === 'new' ? null : editing} onCancel={() => setEditing(null)} onSaved={saved} />
      )}
    </section>
  );
}

function ContactForm({ empId, contact, onCancel, onSaved }: { empId: number; contact: EmergencyContact | null; onCancel: () => void; onSaved: () => void }) {
  const [values, setValues] = useState<Values>(contact ? fromRow(contact) : EMPTY);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const { push } = useToast();
  const { handleError } = useErrorHandler();
  const set = <K extends keyof Values>(k: K, v: Values[K]) => setValues((s) => ({ ...s, [k]: v }));

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const candidate = {
      ...compact({
        contactName: values.contactName,
        relationship: values.relationship,
        phonePrimary: values.phonePrimary,
        phoneSecondary: values.phoneSecondary,
        email: values.email,
        priorityOrder: values.priorityOrder === '' ? undefined : Number(values.priorityOrder),
      }, 'EmergencyContactRequest'),
      active: values.active,
    };
    const parsed = schema.safeParse(candidate);
    if (!parsed.success) {
      setErrors(zodFieldErrors(parsed.error));
      return;
    }
    setErrors({});
    setBusy(true);
    try {
      const body = candidate as unknown as EmergencyContactRequest;
      if (contact) await api.employees.updateEmergencyContact(empId, contact.contactId, body);
      else await api.employees.addEmergencyContact(empId, body);
      push({ kind: 'success', message: contact ? 'Contact updated' : 'Contact added' });
      onSaved();
    } catch (error) {
      setErrors(handleError(error, { fieldNames: FIELDS }).fieldErrors);
    } finally {
      setBusy(false);
    }
  };

  const field = (name: Exclude<keyof Values, 'active'>, label: string, required = false, type = 'text', maxLength?: number) => (
    <div className="field">
      <label htmlFor={`ct-${name}`}>
        {label}
        {required && <span aria-hidden="true"> *</span>}
      </label>
      <input id={`ct-${name}`} type={type} value={values[name]} maxLength={maxLength} onChange={(e) => set(name, e.target.value)} aria-invalid={errors[name] ? true : undefined} />
      {errors[name] && <span role="alert" className="field-error">{errors[name]}</span>}
    </div>
  );

  return (
    <form onSubmit={submit} noValidate aria-label={contact ? 'Edit contact' : 'Add contact'} className="inline-form">
      <div className="form-grid">
        {field('contactName', 'Contact name', true, 'text', 100)}
        {field('relationship', 'Relationship', false, 'text', 30)}
        {field('phonePrimary', 'Primary phone', true, 'tel', 30)}
        {field('phoneSecondary', 'Secondary phone', false, 'tel', 30)}
        {field('email', 'E-mail', false, 'email', 100)}
        {field('priorityOrder', 'Priority', false, 'number')}
        <fieldset className="field">
          <label><input type="checkbox" checked={values.active} onChange={(e) => set('active', e.target.checked)} /> Active</label>
        </fieldset>
      </div>
      <div className="actions">
        <button type="submit" disabled={busy}>{contact ? 'Save contact' : 'Add contact'}</button>
        <button type="button" onClick={onCancel} disabled={busy}>Cancel</button>
      </div>
    </form>
  );
}
