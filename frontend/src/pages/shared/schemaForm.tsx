import type { ChangeEvent } from 'react';
import { fieldErrors as zodFieldErrors, getDto, zodFor, type DtoName, type FieldSpec } from '../../validation/schema';

/**
 * Form plumbing driven by frontend/src/generated/validation-schema.json: the input kind, the HTML
 * constraint attributes and the Zod schema all come from the exported DTO, so no rule is repeated
 * in a page. Values are held as strings (`FormValues`) and coerced by the generated Zod schema.
 */

export type FormValues = Record<string, string | boolean>;

export interface FieldOption {
  value: string;
  label: string;
}

export interface FieldConfig {
  name: string;
  label: string;
  /** Overrides the schema-derived input (e.g. a lookup `select` for a foreign key). */
  options?: FieldOption[];
  placeholder?: string;
  /** The code of an existing row is immutable on update (`PUT … (code immutable)`). */
  readOnly?: boolean;
  hint?: string;
}

export function humanizeCode(code: string | null | undefined): string {
  if (!code) return '—';
  return code.charAt(0) + code.slice(1).toLowerCase().replace(/_/g, ' ');
}

export function emptyValues(dto: DtoName, initial: Record<string, unknown> = {}): FormValues {
  const out: FormValues = {};
  for (const [name, spec] of Object.entries(getDto(dto).fields)) {
    const v = initial[name];
    if (spec.type === 'boolean') out[name] = typeof v === 'boolean' ? v : false;
    else out[name] = v === null || v === undefined ? '' : String(v);
  }
  return out;
}

/** Drop blanks so optional fields are `undefined` for the generated schema, exactly like an absent query parameter. */
export function toCandidate(values: FormValues): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(values)) {
    if (v === '') continue;
    out[k] = v;
  }
  return out;
}

export interface ParseResult<T> {
  data?: T;
  errors: Record<string, string>;
}

export function parseWith<T>(dto: DtoName, values: FormValues): ParseResult<T> {
  const parsed = zodFor(dto).safeParse(toCandidate(values));
  if (!parsed.success) return { errors: zodFieldErrors(parsed.error) };
  return { data: parsed.data as T, errors: {} };
}

/** Wire shape for `decimal` fields is a fixed-scale string (`Money` / `Days`); the Zod schema yields numbers. */
export function serializeDecimals(dto: DtoName, data: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = { ...data };
  for (const [name, spec] of Object.entries(getDto(dto).fields)) {
    const v = out[name];
    if (spec.type === 'decimal' && typeof v === 'number') out[name] = v.toFixed(spec.scale ?? 2);
  }
  return out;
}

interface SchemaFieldProps {
  dto: DtoName;
  field: FieldConfig;
  values: FormValues;
  errors: Record<string, string>;
  onChange: (name: string, value: string | boolean) => void;
  idPrefix?: string;
}

export function SchemaField({ dto, field, values, errors, onChange, idPrefix = '' }: SchemaFieldProps) {
  const spec: FieldSpec | undefined = getDto(dto).fields[field.name];
  if (!spec) throw new Error(`validation-schema.json DTO "${dto}" has no field "${field.name}"`);
  const id = `${idPrefix}${field.name}`;
  const error = errors[field.name];
  const value = values[field.name];
  const invalid = error ? true : undefined;
  const describedBy = error ? `${id}-error` : undefined;

  const change = (e: ChangeEvent<HTMLInputElement | HTMLSelectElement>) => {
    if (e.target instanceof HTMLInputElement && e.target.type === 'checkbox') onChange(field.name, e.target.checked);
    else onChange(field.name, e.target.value);
  };

  let control;
  if (spec.type === 'boolean') {
    control = <input id={id} type="checkbox" checked={value === true} onChange={change} disabled={field.readOnly} aria-invalid={invalid} aria-describedby={describedBy} />;
  } else if (field.options || spec.type === 'enum') {
    const options = field.options ?? (spec.values ?? []).map((v) => ({ value: v, label: humanizeCode(v) }));
    control = (
      <select id={id} value={String(value)} onChange={change} disabled={field.readOnly} aria-invalid={invalid} aria-describedby={describedBy}>
        <option value="">{spec.required ? '— Select —' : 'All'}</option>
        {options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
    );
  } else if (spec.type === 'date') {
    control = <input id={id} type="date" value={String(value)} onChange={change} readOnly={field.readOnly} aria-invalid={invalid} aria-describedby={describedBy} />;
  } else if (spec.type === 'integer' || spec.type === 'decimal') {
    control = (
      <input
        id={id}
        type="number"
        inputMode="decimal"
        step={spec.type === 'integer' ? 1 : 1 / 10 ** (spec.scale ?? 2)}
        min={spec.min !== undefined ? Number(spec.min) : undefined}
        max={spec.max !== undefined ? Number(spec.max) : undefined}
        value={String(value)}
        onChange={change}
        readOnly={field.readOnly}
        placeholder={field.placeholder}
        aria-invalid={invalid}
        aria-describedby={describedBy}
      />
    );
  } else {
    control = (
      <input
        id={id}
        type="text"
        value={String(value)}
        onChange={change}
        maxLength={spec.maxLength}
        readOnly={field.readOnly}
        placeholder={field.placeholder}
        aria-invalid={invalid}
        aria-describedby={describedBy}
      />
    );
  }

  return (
    <div className={`field${spec.type === 'boolean' ? ' field-checkbox' : ''}`}>
      <label htmlFor={id}>
        {field.label}
        {spec.required && <span aria-hidden="true"> *</span>}
      </label>
      {control}
      {field.hint && !error && <small>{field.hint}</small>}
      {error && (
        <span id={`${id}-error`} role="alert" className="field-error">
          {error}
        </span>
      )}
    </div>
  );
}
