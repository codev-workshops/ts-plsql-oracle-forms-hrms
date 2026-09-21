import { z } from 'zod';
import generated from '../generated/validation-schema.json';

/**
 * Loader + Zod adapter for the hrms-validation exporter output
 * (contracts/p0-foundation/README.md "validation-schema.json"). Rules are never written
 * here – every constraint comes from the JSON; the server stays canonical.
 */

export type FieldType = 'string' | 'integer' | 'decimal' | 'boolean' | 'date' | 'enum';
export type RuleKind = 'minLength' | 'maxLength' | 'pattern' | 'min' | 'max' | 'custom';

export interface FieldRule {
  id: string;
  kind: RuleKind;
  value: unknown;
  parameter?: string;
  errorCode: string;
  message: string;
}

export interface FieldMessages {
  required?: string;
  minLength?: string;
  maxLength?: string;
  pattern?: string;
  format?: string;
  min?: string;
  max?: string;
}

export interface FieldSpec {
  type: FieldType;
  required: boolean;
  trim?: boolean;
  minLength?: number;
  maxLength?: number;
  pattern?: string;
  format?: string;
  min?: number | string;
  max?: number | string;
  values?: string[];
  rules?: FieldRule[];
  messages: FieldMessages;
}

export interface DtoSpec {
  fields: Record<string, FieldSpec>;
}

export interface ValidationSchema {
  $schema: string;
  schemaVersion: number;
  generator: string;
  generatorVersion: string;
  sourceHash: string;
  module: string;
  parameters: Record<string, number | string | boolean>;
  dtos: Record<string, DtoSpec>;
}

export const validationSchema: ValidationSchema = generated as unknown as ValidationSchema;

export type DtoName = keyof typeof generated.dtos;

export function getDto(name: DtoName): DtoSpec {
  const dto = validationSchema.dtos[name];
  if (!dto) throw new Error(`validation-schema.json has no DTO "${name}"`);
  return dto;
}

export function getParameter(name: string): number | string | boolean {
  const v = validationSchema.parameters[name];
  if (v === undefined) throw new Error(`validation-schema.json has no parameter "${name}"`);
  return v;
}

export interface RuleFailure {
  errorCode: string;
  message: string;
  ruleId: string;
}

/**
 * Evaluate the ordered custom `rules` of a field – first failure wins, exactly like the
 * PL/SQL sequence they were exported from (PKG_SECURITY.change_password → -20310/-20311/-20312).
 */
export function evaluateRules(field: FieldSpec, value: string): RuleFailure | null {
  for (const rule of field.rules ?? []) {
    let ok = true;
    switch (rule.kind) {
      case 'minLength':
        ok = value.length >= Number(rule.value);
        break;
      case 'maxLength':
        ok = value.length <= Number(rule.value);
        break;
      case 'pattern':
        ok = new RegExp(String(rule.value)).test(value);
        break;
      case 'min':
        ok = Number(value) >= Number(rule.value);
        break;
      case 'max':
        ok = Number(value) <= Number(rule.value);
        break;
      case 'custom':
        // Not evaluable client-side: server remains canonical.
        ok = true;
        break;
    }
    if (!ok) return { errorCode: rule.errorCode, message: rule.message, ruleId: rule.id };
  }
  return null;
}

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

function stringField(spec: FieldSpec): z.ZodTypeAny {
  const m = spec.messages;
  let s = z.string();
  if (spec.trim) s = s.trim();
  if (spec.required) s = s.min(1, m.required ?? 'Required');
  // A bound that is also expressed as an ordered rule is left to the rule so its legacy message/code wins.
  const ruledKinds = new Set((spec.rules ?? []).map((r) => r.kind));
  if (spec.minLength !== undefined && !ruledKinds.has('minLength')) {
    s = s.min(spec.minLength, m.minLength ?? `Must be at least ${spec.minLength} characters`);
  }
  if (spec.maxLength !== undefined && !ruledKinds.has('maxLength')) {
    s = s.max(spec.maxLength, m.maxLength ?? `Must be at most ${spec.maxLength} characters`);
  }
  if (spec.pattern) s = s.regex(new RegExp(spec.pattern), m.pattern ?? 'Invalid format');
  if (spec.format === 'email') s = s.regex(EMAIL_RE, m.format ?? 'Enter a valid e-mail address');

  const withRules = s.superRefine((value, ctx) => {
    const failure = evaluateRules(spec, value);
    if (failure) ctx.addIssue({ code: z.ZodIssueCode.custom, message: failure.message, params: { errorCode: failure.errorCode } });
  });
  return spec.required ? withRules : withRules.optional().or(z.literal('').transform(() => undefined));
}

function numberField(spec: FieldSpec): z.ZodTypeAny {
  const m = spec.messages;
  let n = spec.type === 'integer' ? z.number().int() : z.number();
  if (spec.min !== undefined) n = n.min(Number(spec.min), m.min);
  if (spec.max !== undefined) n = n.max(Number(spec.max), m.max);
  const coerced = z.preprocess((v) => (v === '' || v === null ? undefined : typeof v === 'string' ? Number(v) : v), n);
  return spec.required ? coerced : coerced.optional();
}

function fieldToZod(spec: FieldSpec): z.ZodTypeAny {
  switch (spec.type) {
    case 'string':
      return stringField(spec);
    case 'integer':
    case 'decimal':
      return numberField(spec);
    case 'boolean':
      return spec.required ? z.boolean() : z.boolean().optional();
    case 'date':
      return spec.required ? z.string().date(spec.messages.format) : z.string().date(spec.messages.format).optional();
    case 'enum': {
      const values = spec.values ?? [];
      const e = z.enum(values as [string, ...string[]], { message: spec.messages.required });
      return spec.required ? e : e.optional();
    }
  }
}

const cache = new Map<string, z.ZodObject<Record<string, z.ZodTypeAny>>>();

/** Build (and memoise) a Zod object schema for a DTO from the generated JSON. */
export function zodFor(name: DtoName): z.ZodObject<Record<string, z.ZodTypeAny>> {
  const hit = cache.get(name);
  if (hit) return hit;
  const dto = getDto(name);
  const shape: Record<string, z.ZodTypeAny> = {};
  for (const [field, spec] of Object.entries(dto.fields)) shape[field] = fieldToZod(spec);
  const obj = z.object(shape);
  cache.set(name, obj);
  return obj;
}

/** Flatten a Zod error into `{ field: firstMessage }` for inline display. */
export function fieldErrors(error: z.ZodError): Record<string, string> {
  const out: Record<string, string> = {};
  for (const issue of error.issues) {
    const key = String(issue.path[0] ?? '');
    if (key && !(key in out)) out[key] = issue.message;
  }
  return out;
}
