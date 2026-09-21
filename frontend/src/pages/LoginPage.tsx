import { useEffect, useState, type FormEvent } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../app/AuthContext';
import { useErrorHandler } from '../app/useErrorHandler';
import { fieldErrors as zodFieldErrors, zodFor } from '../validation/schema';

const loginSchema = zodFor('LoginRequest');
const FIELDS = ['username', 'password'] as const;

/** Replaces HRMS_LOGIN (COMPONENT_MAPPING.md §1). */
export function LoginPage() {
  const { status, login, sessionMessage, clearSessionMessage } = useAuth();
  const { handleError } = useErrorHandler();
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as { from?: { pathname: string } } | null)?.from?.pathname ?? '/';

  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => () => clearSessionMessage(), [clearSessionMessage]);

  if (status === 'authenticated') return <Navigate to={from} replace />;

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setFormError(null);
    const parsed = loginSchema.safeParse({ username, password });
    if (!parsed.success) {
      setErrors(zodFieldErrors(parsed.error));
      return;
    }
    setErrors({});
    setSubmitting(true);
    try {
      const t = await login(parsed.data as { username: string; password: string });
      navigate(t.user.mustChangePassword ? '/password' : from, { replace: true, state: t.user.mustChangePassword ? { forced: true } : undefined });
    } catch (err) {
      // Login failures are shown inline on the form (one message for every cause – no user enumeration).
      const handled = handleError(err, { fieldNames: FIELDS, toast: false });
      if (Object.keys(handled.fieldErrors).length) setErrors(handled.fieldErrors);
      else setFormError(handled.message);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="login-page">
      <form className="login-form" onSubmit={onSubmit} noValidate aria-labelledby="login-title">
        <img src="/logo.svg" alt="" className="company-logo" width={96} height={96} />
        <h1 id="login-title">HR Management System</h1>
        {sessionMessage && (
          <p role="status" className="session-message">
            {sessionMessage}
          </p>
        )}
        <div className="field">
          <label htmlFor="username">E-mail</label>
          <input
            id="username"
            name="username"
            type="email"
            autoComplete="username"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            aria-invalid={errors.username ? true : undefined}
            aria-describedby={errors.username ? 'username-error' : undefined}
            autoFocus
          />
          {errors.username && (
            <span id="username-error" role="alert" className="field-error">
              {errors.username}
            </span>
          )}
        </div>
        <div className="field">
          <label htmlFor="password">Password</label>
          <input
            id="password"
            name="password"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            aria-invalid={errors.password ? true : undefined}
            aria-describedby={errors.password ? 'password-error' : undefined}
          />
          {errors.password && (
            <span id="password-error" role="alert" className="field-error">
              {errors.password}
            </span>
          )}
        </div>
        {formError && (
          <p id="error-msg" role="alert" className="form-error">
            {formError}
          </p>
        )}
        <button type="submit" disabled={submitting}>
          {submitting ? 'Signing in…' : 'Login'}
        </button>
      </form>
    </div>
  );
}
