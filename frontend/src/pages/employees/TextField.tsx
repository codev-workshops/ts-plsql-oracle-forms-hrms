import type { ChangeEvent } from 'react';

interface Props {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  type?: 'text' | 'date' | 'email' | 'number' | 'tel' | 'password';
  required?: boolean;
  disabled?: boolean;
  error?: string;
  options?: readonly string[];
  multiline?: boolean;
  step?: string;
  min?: string;
  autoComplete?: string;
}

/** Labelled input/select/textarea with the shared `.field` error contract (role=alert). */
export function TextField({ id, label, value, onChange, type = 'text', required, disabled, error, options, multiline, step, min, autoComplete }: Props) {
  const handle = (e: ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) => onChange(e.target.value);
  const common = { id, value, onChange: handle, disabled, 'aria-invalid': error ? true : undefined, 'aria-describedby': error ? `${id}-error` : undefined };
  return (
    <div className="field">
      <label htmlFor={id}>
        {label}
        {required && <span aria-hidden="true"> *</span>}
      </label>
      {options ? (
        <select {...common}>
          <option value="">— Select —</option>
          {options.map((o) => (
            <option key={o} value={o}>
              {o.replace(/_/g, ' ')}
            </option>
          ))}
        </select>
      ) : multiline ? (
        <textarea {...common} />
      ) : (
        <input {...common} type={type} step={step} min={min} autoComplete={autoComplete} />
      )}
      {error && (
        <span id={`${id}-error`} role="alert" className="field-error">
          {error}
        </span>
      )}
    </div>
  );
}
