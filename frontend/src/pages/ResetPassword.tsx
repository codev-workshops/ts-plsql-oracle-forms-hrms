import { useState, type FormEvent } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { api, call } from '../api/client';
import { ErrorBanner } from '../components/ui';

/** Target of the one-time reset link (`/reset-password#token=…`, OQ-15, contract v1.5). Public page. */
export function ResetPassword() {
  const { hash } = useLocation();
  const token = new URLSearchParams(hash.replace(/^#/, '')).get('token') ?? '';
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [done, setDone] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (password !== confirm) {
      setError(new Error('The passwords do not match.'));
      return;
    }
    try {
      await call(api.POST('/api/v1/auth/password-reset/confirm', { body: { token, newPassword: password } }));
      setDone(true);
    } catch (err) {
      setError(err);
    }
  }

  if (done) {
    return (
      <div className="login form">
        <h1>Password set</h1>
        <p role="status">Your password has been set. You can now sign in.</p>
        <Link to="/">Sign in</Link>
      </div>
    );
  }
  return (
    <form className="login form" onSubmit={submit} aria-label="Set password">
      <h1>Set your HRMS password</h1>
      {!token && <p className="muted">This link is incomplete. Ask HR for a new password link.</p>}
      <div className="field">
        <label htmlFor="new-password">New password</label>
        <input id="new-password" type="password" required value={password} onChange={(e) => setPassword(e.target.value)} />
      </div>
      <div className="field">
        <label htmlFor="confirm-password">Repeat new password</label>
        <input id="confirm-password" type="password" required value={confirm} onChange={(e) => setConfirm(e.target.value)} />
      </div>
      <p className="muted">At least 8 characters, with an uppercase letter and a digit.</p>
      <ErrorBanner error={error} />
      <button type="submit" disabled={!token}>
        Set password
      </button>
    </form>
  );
}
