import { useState, type FormEvent } from 'react';
import { useAuth } from '../auth/AuthContext';
import { ErrorBanner } from '../components/ui';

export function Login() {
  const { login, notice } = useAuth();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(username, password);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="login form" onSubmit={submit} aria-label="Sign in">
      <h1>HRMS sign in</h1>
      {notice && <p className="muted">{notice}</p>}
      <div className="field">
        <label htmlFor="username">Work email</label>
        <input id="username" type="email" required value={username} onChange={(e) => setUsername(e.target.value)} />
      </div>
      <div className="field">
        <label htmlFor="password">Password</label>
        <input id="password" type="password" required value={password} onChange={(e) => setPassword(e.target.value)} />
      </div>
      <ErrorBanner error={error} />
      <button type="submit" disabled={busy}>
        Sign in
      </button>
    </form>
  );
}
