import { useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { api } from '../../api/client';
import type { EmployeeDetail, EmployeeTransferRequest } from '../../api/types';
import { useToast } from '../../app/ToastContext';
import { useErrorHandler } from '../../app/useErrorHandler';
import { ReferenceDropdown } from '../../components/ReferenceDropdown';
import { getDto } from '../../validation/schema';
import { Dialog } from './Dialog';
import { isRequired, parseForm, todayIso, type FormValues } from './formUtils';
import { employeeKeys } from './queryKeys';
import { TextField } from './TextField';

const DTO = 'EmployeeTransferRequest';
const FIELDS = Object.keys(getDto(DTO).fields);

/** `BTN_TRANSFER` (HRMS_EMPLOYEE.fmb) → `POST /api/employees/{id}/transfer`; -20012 for non-ACTIVE. */
export function TransferDialog({ employee, onClose, onDone }: { employee: EmployeeDetail; onClose: () => void; onDone: (e: EmployeeDetail) => void }) {
  const qc = useQueryClient();
  const [form, setForm] = useState<FormValues>({
    effectiveDate: todayIso(),
    deptId: String(employee.deptId),
    newJobId: '',
    newManagerEmpId: '',
    newLocationCode: '',
    reasonCode: '',
    comments: '',
  });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const { handleError } = useErrorHandler();
  const { push } = useToast();
  const set = (k: string) => (v: string) => setForm((o) => ({ ...o, [k]: v }));

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const parsed = parseForm<EmployeeTransferRequest>(DTO, form);
    if (!parsed.ok) {
      setErrors(parsed.errors);
      return;
    }
    setErrors({});
    setBusy(true);
    try {
      const updated = await api.employees.transferEmployee(employee.id, parsed.data);
      qc.setQueryData(employeeKeys.detail(employee.id), updated);
      void qc.invalidateQueries({ queryKey: employeeKeys.all });
      push({ kind: 'success', message: `Transferred to ${updated.deptName}` });
      onDone(updated);
    } catch (error) {
      setErrors(handleError(error, { fieldNames: FIELDS }).fieldErrors);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog title="Transfer employee" onSubmit={submit} onClose={onClose} busy={busy} submitLabel="Transfer">
      <p>
        Current: {employee.deptName} · {employee.jobTitle} · {employee.locationName ?? 'no location'}
      </p>
      <TextField id="xfer-effectiveDate" label="Effective date" type="date" value={form.effectiveDate} onChange={set('effectiveDate')} required={isRequired(DTO, 'effectiveDate')} error={errors.effectiveDate} />
      <ReferenceDropdown source="departments" label="New department" name="deptId" value={form.deptId || null} onChange={(v) => set('deptId')(v ?? '')} required={isRequired(DTO, 'deptId')} error={errors.deptId} />
      <ReferenceDropdown source="job-titles" label="New job title (optional)" name="newJobId" value={form.newJobId || null} onChange={(v) => set('newJobId')(v ?? '')} error={errors.newJobId} placeholder="Keep current" />
      <ReferenceDropdown source="managers" label="New manager (optional)" name="newManagerEmpId" value={form.newManagerEmpId || null} onChange={(v) => set('newManagerEmpId')(v ?? '')} excludeSelf error={errors.newManagerEmpId} placeholder="Keep current" />
      <ReferenceDropdown source="locations" label="New location (optional)" name="newLocationCode" value={form.newLocationCode || null} onChange={(v) => set('newLocationCode')(v ?? '')} error={errors.newLocationCode} placeholder="Keep current" />
      <TextField id="xfer-reasonCode" label="Reason code" value={form.reasonCode} onChange={set('reasonCode')} error={errors.reasonCode} />
      <TextField id="xfer-comments" label="Comments" multiline value={form.comments} onChange={set('comments')} error={errors.comments} />
    </Dialog>
  );
}
