import { useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { api } from '../../api/client';
import type { EmployeeDetail, EmployeeTerminateRequest } from '../../api/types';
import { useToast } from '../../app/ToastContext';
import { useErrorHandler } from '../../app/useErrorHandler';
import { getDto } from '../../validation/schema';
import { Dialog } from './Dialog';
import { isRequired, parseForm, todayIso, type FormValues } from './formUtils';
import { employeeKeys } from './queryKeys';
import { TextField } from './TextField';

const DTO = 'EmployeeTerminateRequest';
const FIELDS = Object.keys(getDto(DTO).fields);

/** `BTN_TERMINATE` (HRMS_EMPLOYEE.fmb) → `POST /api/employees/{id}/terminate`; -20005 if already terminated. */
export function TerminateDialog({ employee, onClose, onDone }: { employee: EmployeeDetail; onClose: () => void; onDone: (e: EmployeeDetail) => void }) {
  const qc = useQueryClient();
  const [form, setForm] = useState<FormValues>({ effectiveDate: todayIso(), reason: '', comments: '' });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const { handleError } = useErrorHandler();
  const { push } = useToast();
  const set = (k: string) => (v: string) => setForm((o) => ({ ...o, [k]: v }));

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const parsed = parseForm<EmployeeTerminateRequest>(DTO, form);
    if (!parsed.ok) {
      setErrors(parsed.errors);
      return;
    }
    setErrors({});
    setBusy(true);
    try {
      const updated = await api.employees.terminateEmployee(employee.id, parsed.data);
      qc.setQueryData(employeeKeys.detail(employee.id), updated);
      void qc.invalidateQueries({ queryKey: employeeKeys.all });
      push({ kind: 'success', message: `${updated.firstName} ${updated.lastName} terminated effective ${parsed.data.effectiveDate}` });
      onDone(updated);
    } catch (error) {
      setErrors(handleError(error, { fieldNames: FIELDS }).fieldErrors);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog title="Terminate employee" onSubmit={submit} onClose={onClose} busy={busy} submitLabel="Terminate" destructive>
      <p>
        Terminating <strong>{employee.firstName} {employee.lastName}</strong> ({employee.empNumber}) closes the active salary record and cannot be undone here – reactivation goes through the rehire process.
      </p>
      <TextField id="term-effectiveDate" label="Effective date" type="date" value={form.effectiveDate} onChange={set('effectiveDate')} required={isRequired(DTO, 'effectiveDate')} error={errors.effectiveDate} />
      <TextField id="term-reason" label="Reason" value={form.reason} onChange={set('reason')} required={isRequired(DTO, 'reason')} error={errors.reason} />
      <TextField id="term-comments" label="Comments" multiline value={form.comments} onChange={set('comments')} error={errors.comments} />
    </Dialog>
  );
}
