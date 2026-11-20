import { useCallback, useEffect, useState, type FormEvent, type ReactNode } from 'react';
import { errorMessage } from '../api/client';

export function ErrorBanner({ error }: { error: unknown }) {
  if (!error) return null;
  return (
    <div role="alert" className="error">
      {errorMessage(error)}
    </div>
  );
}

/** Loads data on mount and whenever {@code deps} change; {@code reload} re-runs the loader. */
export function useLoad<T>(loader: () => Promise<T>, deps: unknown[]) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const run = useCallback(loader, deps);
  const reload = useCallback(() => {
    setLoading(true);
    run()
      .then((d) => {
        setData(d);
        setError(null);
      })
      .catch(setError)
      .finally(() => setLoading(false));
  }, [run]);
  useEffect(reload, [reload]);
  return { data, error, loading, reload };
}

export interface Column<T> {
  header: string;
  render: (row: T) => ReactNode;
}

export function DataTable<T>({ rows, columns, empty = 'No rows', label }: {
  rows: T[] | null | undefined;
  columns: Column<T>[];
  empty?: string;
  label: string;
}) {
  if (!rows) return null;
  if (rows.length === 0) return <p className="muted">{empty}</p>;
  return (
    <table aria-label={label}>
      <thead>
        <tr>
          {columns.map((c) => (
            <th key={c.header}>{c.header}</th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.map((r, i) => (
          <tr key={i}>
            {columns.map((c) => (
              <td key={c.header}>{c.render(r)}</td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

export interface FieldSpec {
  name: string;
  label: string;
  type?: 'text' | 'date' | 'number' | 'email' | 'password' | 'textarea' | 'select' | 'checkbox';
  required?: boolean;
  options?: { value: string; label: string }[];
  hint?: (value: string) => string | null;
  step?: string;
}

export type FormValues = Record<string, string>;

/** A small form: every value is a string; the caller converts it to the request body. */
export function SimpleForm({ fields, submitLabel, onSubmit, initial = {}, title }: {
  fields: FieldSpec[];
  submitLabel: string;
  onSubmit: (values: FormValues) => Promise<unknown>;
  initial?: FormValues;
  title?: string;
}) {
  const [values, setValues] = useState<FormValues>(initial);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState<string | null>(null);
  const hints = fields.map((f) => (f.hint ? f.hint(values[f.name] ?? '') : null));

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setDone(null);
    try {
      await onSubmit(values);
      setDone(`${submitLabel}: done`);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="form" aria-label={title ?? submitLabel}>
      {title && <h3>{title}</h3>}
      {fields.map((f, i) => {
        const id = `${title ?? submitLabel}-${f.name}`.replace(/\s+/g, '-');
        const common = {
          id,
          name: f.name,
          required: f.required,
          value: values[f.name] ?? '',
          onChange: (e: { target: { value: string } }) => setValues((v) => ({ ...v, [f.name]: e.target.value })),
        };
        return (
          <div key={f.name} className="field">
            <label htmlFor={id}>
              {f.label}
              {f.required ? ' *' : ''}
            </label>
            {f.type === 'textarea' ? (
              <textarea {...common} />
            ) : f.type === 'select' ? (
              <select {...common}>
                <option value="">—</option>
                {f.options?.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            ) : f.type === 'checkbox' ? (
              <input
                id={id}
                type="checkbox"
                checked={values[f.name] === 'true'}
                onChange={(e) => setValues((v) => ({ ...v, [f.name]: String(e.target.checked) }))}
              />
            ) : (
              <input {...common} type={f.type ?? 'text'} step={f.step} />
            )}
            {hints[i] && <span className="hint">{hints[i]}</span>}
          </div>
        );
      })}
      <ErrorBanner error={error} />
      {done && <p className="ok">{done}</p>}
      <button type="submit" disabled={busy || hints.some(Boolean)}>
        {submitLabel}
      </button>
    </form>
  );
}

/** Converts form strings to a request body: '' → omitted, numeric fields → numbers. */
export function body(values: FormValues, numeric: string[] = [], booleans: string[] = []) {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(values)) {
    if (v === '' || v === undefined) continue;
    if (numeric.includes(k)) out[k] = Number(v);
    else if (booleans.includes(k)) out[k] = v === 'true';
    else out[k] = v;
  }
  return out;
}

export function ActionButton({ label, onClick, disabled, title }: {
  label: string;
  onClick: () => Promise<unknown>;
  disabled?: boolean;
  title?: string;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  return (
    <span>
      <button
        type="button"
        disabled={disabled || busy}
        title={title}
        onClick={() => {
          setBusy(true);
          setError(null);
          onClick()
            .catch(setError)
            .finally(() => setBusy(false));
        }}
      >
        {label}
      </button>
      <ErrorBanner error={error} />
    </span>
  );
}
