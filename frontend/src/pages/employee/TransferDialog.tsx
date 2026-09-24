import { useState, type FormEvent } from 'react';
import { api } from '../../api/client';
import type { EmployeeDetail, EmployeeTransferRequest } from '../../api/types';
import { useToast } from '../../app/ToastContext';
import { useErrorHandler } from '../../app/useErrorHandler';
import { ReferenceDropdown } from '../../components/ReferenceDropdown';
import { fieldErrors as zodFieldErrors, zodFor } from '../../validation/schema';
import { compact } from './employeeShared';

const schema = zodFor('EmployeeTransferRequest');
const FIELDS = ['effectiveDate', 'deptId', 'newJobId', 'newManagerEmpId', 'newLocationCode', 'reasonCode', 'comments'] as const;

/** `BTN_TRANSFER` (HRMS_EMPLOYEE.xml) → `POST /api/employees/{id}/transfer`; department is required, the rest optional. */
export function TransferDialog({ employee, onClose, onTransferred }: { employee: EmployeeDetail; onClose: () => void; onTransferred: (employee: EmployeeDetail) => void }) {
  const [effectiveDate, setEffectiveDate] = useState('');
  const [deptId, setDeptId] = useState<string | null>(String(employee.deptId));
  const [newJobId, setNewJobId] = useState<string | null>(null);
  const [newManagerEmpId, setNewManagerEmpId] = useState<string | null>(null);
  const [newLocationCode, setNewLocationCode] = useState<string | null>(null);
  const [reasonCode, setReasonCode] = useState('');
  const [comments, setComments] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const { handleError } = useErrorHandler();
  const { push } = useToast();

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const candidate = compact({
      effectiveDate,
      deptId: deptId ? Number(deptId) : undefined,
      newJobId: newJobId ? Number(newJobId) : undefined,
      newManagerEmpId: newManagerEmpId ? Number(newManagerEmpId) : undefined,
      newLocationCode,
      reasonCode: reasonCode.trim(),
      comments: comments.trim(),
    }, 'EmployeeTransferRequest');
    const parsed = schema.safeParse(candidate);
    if (!parsed.success) {
      setErrors(zodFieldErrors(parsed.error));
      return;
    }
    setErrors({});
    setBusy(true);
    try {
      const updated = await api.employees.transferEmployee(employee.id, candidate as unknown as EmployeeTransferRequest);
      push({ kind: 'success', message: `Employee ${employee.empNumber} transferred to ${updated.deptName}` });
      onTransferred(updated);
    } catch (error) {
      setErrors(handleError(error, { fieldNames: FIELDS }).fieldErrors);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="dialog-overlay">
      <div role="dialog" aria-modal="true" aria-labelledby="transfer-dialog-title" className="dialog">
        <h3 id="transfer-dialog-title">Transfer employee</h3>
        <p>
          {employee.lastName}, {employee.firstName} ({employee.empNumber}) · currently {employee.deptName} / {employee.jobTitle}
        </p>
        <form onSubmit={submit} noValidate>
          <div className="field">
            <label htmlFor="xferEffectiveDate">Effective date <span aria-hidden="true">*</span></label>
            <input id="xferEffectiveDate" type="date" value={effectiveDate} onChange={(e) => setEffectiveDate(e.target.value)} aria-invalid={errors.effectiveDate ? true : undefined} />
            {errors.effectiveDate && <span role="alert" className="field-error">{errors.effectiveDate}</span>}
          </div>
          <ReferenceDropdown source="departments" label="New department" required value={deptId} onChange={setDeptId} error={errors.deptId} />
          <ReferenceDropdown source="job-titles" label="New job title" value={newJobId} onChange={setNewJobId} error={errors.newJobId} placeholder="— Keep current —" />
          <ReferenceDropdown source="managers" label="New manager" value={newManagerEmpId} onChange={setNewManagerEmpId} error={errors.newManagerEmpId} placeholder="— Keep current —" />
          <ReferenceDropdown source="locations" label="New location" value={newLocationCode} onChange={setNewLocationCode} error={errors.newLocationCode} placeholder="— Keep current —" />
          <div className="field">
            <label htmlFor="xferReasonCode">Reason code</label>
            <input id="xferReasonCode" value={reasonCode} maxLength={50} onChange={(e) => setReasonCode(e.target.value)} aria-invalid={errors.reasonCode ? true : undefined} />
            {errors.reasonCode && <span role="alert" className="field-error">{errors.reasonCode}</span>}
          </div>
          <div className="field">
            <label htmlFor="xferComments">Comments</label>
            <textarea id="xferComments" value={comments} maxLength={4000} onChange={(e) => setComments(e.target.value)} aria-invalid={errors.comments ? true : undefined} />
            {errors.comments && <span role="alert" className="field-error">{errors.comments}</span>}
          </div>
          <div className="actions">
            <button type="submit" disabled={busy}>Confirm transfer</button>
            <button type="button" onClick={onClose} disabled={busy}>Cancel</button>
          </div>
        </form>
      </div>
    </div>
  );
}
