import { useState, type FormEvent } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import { useAuth } from '../app/AuthContext';
import { useToast } from '../app/ToastContext';
import { useErrorHandler } from '../app/useErrorHandler';
import { fieldErrors as zodFieldErrors, getDto, getParameter, zodFor } from '../validation/schema';

const schema = zodFor('ChangePasswordRequest');
const FIELDS = ['currentPassword', 'newPassword'] as const;

/**
 * Replaces HRMS_MENU `MI_CHANGE_PWD` → `PUT /api/auth/password` (COMPONENT_MAPPING.md §1, §2).
 * Also the forced first-login set-password flow (`mustChangePassword`).
 */
export function ChangePasswordPage() {
  const { user, refreshUser } = useAuth();
  const { handleError } = useErrorHandler();
  const { push } = useToast();
  const navigate = useNavigate();
  const location = useLocation();
  const forced = Boolean((location.state as { forced?: boolean } | null)?.forced) || Boolean(user?.mustChangePassword);

  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);

  const minLength = Number(getParameter('SECURITY.PASSWORD_MIN_LENGTH'));
  const policy = getDto('ChangePasswordRequest').fields.newPassword.rules ?? [];

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    const parsed = schema.safeParse({ currentPassword, newPassword });
    const nextErrors = parsed.success ? {} : zodFieldErrors(parsed.error);
    if (newPassword !== confirm) nextErrors.confirm = 'Passwords do not match';
    if (Object.keys(nextErrors).length) {
      setErrors(nextErrors);
      return;
    }
    setErrors({});
    setSubmitting(true);
    try {
      await api.auth.changePassword({ currentPassword, newPassword });
      await refreshUser();
      push({ kind: 'success', message: 'Password changed' });
      navigate('/', { replace: true });
    } catch (err) {
      const handled = handleError(err, { fieldNames: FIELDS });
      if (Object.keys(handled.fieldErrors).length) setErrors(handled.fieldErrors);
    } finally {
      setSubmitting(false);
    }
  };

  const field = (id: keyof typeof errors, label: string, value: string, set: (v: string) => void, autoComplete: string) => (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <input
        id={id}
        name={id}
        type="password"
        autoComplete={autoComplete}
        value={value}
        onChange={(e) => set(e.target.value)}
        aria-invalid={errors[id] ? true : undefined}
        aria-describedby={errors[id] ? `${id}-error` : undefined}
      />
      {errors[id] && (
        <span id={`${id}-error`} role="alert" className="field-error">
          {errors[id]}
        </span>
      )}
    </div>
  );

  return (
    <div className="password-page">
      <form onSubmit={onSubmit} noValidate aria-labelledby="pwd-title">
        <h1 id="pwd-title">{forced ? 'Set your password' : 'Change password'}</h1>
        {forced && (
          <p role="status" className="session-message">
            You must set a new password before continuing.
          </p>
        )}
        {field('currentPassword', 'Current password', currentPassword, setCurrentPassword, 'current-password')}
        {field('newPassword', 'New password', newPassword, setNewPassword, 'new-password')}
        {field('confirm', 'Confirm new password', confirm, setConfirm, 'new-password')}
        <ul className="password-policy" aria-label="Password rules">
          {policy.map((r) => (
            <li key={r.id}>{r.message}</li>
          ))}
        </ul>
        <p className="hint">Minimum length: {minLength} characters.</p>
        <div className="actions">
          <button type="submit" disabled={submitting}>
            {submitting ? 'Saving…' : 'Save'}
          </button>
          {!forced && (
            <button type="button" onClick={() => navigate(-1)} disabled={submitting}>
              Cancel
            </button>
          )}
        </div>
      </form>
    </div>
  );
}
