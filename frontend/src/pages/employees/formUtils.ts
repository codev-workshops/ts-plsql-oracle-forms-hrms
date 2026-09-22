import type { z } from 'zod';
import { type DtoName, fieldErrors as zodFieldErrors, getDto, zodFor } from '../../validation/schema';

/** Text-input state (every field a string) for a generated DTO. */
export type FormValues = Record<string, string>;

/**
 * Turn form strings into the value shape the generated Zod schema expects: blanks become
 * `undefined` (so optional fields are omitted from the request body) and numeric DTO fields
 * are handed over as strings for the adapter's own coercion.
 */
export function toCandidate(dto: DtoName, values: FormValues): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [name, spec] of Object.entries(getDto(dto).fields)) {
    const raw = values[name];
    if (raw === undefined) continue;
    const trimmed = spec.type === 'string' ? raw.trim() : raw;
    if (trimmed === '') {
      if (spec.required && (spec.type === 'string' || spec.type === 'date')) out[name] = '';
      continue;
    }
    out[name] = spec.type === 'boolean' ? raw === 'true' : trimmed;
  }
  return out;
}

export type Parsed<T> = { ok: true; data: T } | { ok: false; errors: Record<string, string> };

/** Validate with the generated schema; the parsed output is the contract request body. */
export function parseForm<T>(dto: DtoName, values: FormValues): Parsed<T> {
  const schema: z.ZodTypeAny = zodFor(dto);
  const result = schema.safeParse(toCandidate(dto, values));
  if (!result.success) return { ok: false, errors: zodFieldErrors(result.error) };
  // Contract fields without generated rules (e.g. `hireDateFrom`) pass through untouched.
  const known = getDto(dto).fields;
  const extras: Record<string, string> = {};
  for (const [name, raw] of Object.entries(values)) {
    if (!(name in known) && raw.trim() !== '') extras[name] = raw.trim();
  }
  return { ok: true, data: { ...extras, ...(result.data as Record<string, unknown>) } as T };
}

export function enumValues(dto: DtoName, field: string): string[] {
  return getDto(dto).fields[field]?.values ?? [];
}

export function isRequired(dto: DtoName, field: string): boolean {
  return getDto(dto).fields[field]?.required ?? false;
}

export function money(n: number): string {
  return n.toFixed(2);
}

export function todayIso(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}
