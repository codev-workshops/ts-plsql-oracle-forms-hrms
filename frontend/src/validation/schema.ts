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
  scale?: number;
  values?: string[];
  rules?: FieldRule[];
  sensitive?: boolean;
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
        // Cross-field / clock-relative rules are evaluated by `evaluateCustomRule` at object level.
        ok = true;
        break;
    }
    if (!ok) return { errorCode: rule.errorCode, message: rule.message, ruleId: rule.id };
  }
  return null;
}

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

const DAY_MS = 86_400_000;

function utcDay(iso: string): number | null {
  const t = Date.parse(`${iso}T00:00:00Z`);
  return Number.isNaN(t) ? null : Math.floor(t / DAY_MS);
}

function todayUtcDay(): number {
  const now = new Date();
  return Math.floor(Date.UTC(now.getFullYear(), now.getMonth(), now.getDate()) / DAY_MS);
}

const HOLIDAY_FLOOR = '1990-01-01';

/** `LocalDate.plusYears(n)` on the given ISO date: calendar years, 29 Feb clamps to 28 Feb. */
export function plusYears(iso: string, years: number): string {
  const [y, m, d] = iso.split('-').map(Number);
  const target = new Date(Date.UTC(y + years, m - 1, 1));
  const lastDay = new Date(Date.UTC(y + years, m, 0)).getUTCDate();
  target.setUTCDate(Math.min(d, lastDay));
  return target.toISOString().slice(0, 10);
}

function todayIso(): string {
  return new Date(todayUtcDay() * DAY_MS).toISOString().slice(0, 10);
}

/**
 * Object-level evaluation of the exported `custom` rules whose operands live outside the field
 * itself. The rule id names the semantics, `rule.value` carries the operand (a sibling field for
 * `leave.dateOrder`, a day count for `leave.pastLimit` / `employee.hireDateLimit`); nothing here
 * is a constant of its own.
 * Unknown ids are left to the server. Returns `false` when the rule fails.
 */
export function evaluateCustomRule(rule: FieldRule, value: unknown, values: Record<string, unknown>): boolean {
  if (typeof value !== 'string' || value === '') return true;
  const day = utcDay(value);
  if (day === null) return true;
  switch (rule.id) {
    case 'leave.dateOrder':
    case 'audit.range':
    case 'files.range': {
      const other = values[String(rule.value)];
      const otherDay = typeof other === 'string' ? utcDay(other) : null;
      return otherDay === null || otherDay <= day;
    }
    case 'leave.pastLimit':
      return day >= todayUtcDay() - Number(rule.value);
    case 'employee.hireDateLimit':
      return day <= todayUtcDay() + Number(rule.parameter ? getParameter(rule.parameter) : rule.value);
    case 'employee.dateNotFuture':
      return day <= todayUtcDay();
    case 'holiday.dateWindow':
      return value >= HOLIDAY_FLOOR && value <= plusYears(todayIso(), Number(rule.value));
    default:
      return true;
  }
}

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
  const base = z.number({ required_error: m.required ?? 'Required', invalid_type_error: m.format ?? m.required ?? 'Invalid number' });
  let n = spec.type === 'integer' ? base.int() : base;
  const ruledKinds = new Set((spec.rules ?? []).map((r) => r.kind));
  if (spec.min !== undefined && !ruledKinds.has('min')) n = n.min(Number(spec.min), m.min);
  if (spec.max !== undefined && !ruledKinds.has('max')) n = n.max(Number(spec.max), m.max);
  const withRules = n.superRefine((value, ctx) => {
    const failure = evaluateRules(spec, String(value));
    if (failure) ctx.addIssue({ code: z.ZodIssueCode.custom, message: failure.message, params: { errorCode: failure.errorCode } });
  });
  const coerced = z.preprocess((v) => (v === '' || v === null ? undefined : typeof v === 'string' ? Number(v) : v), withRules);
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
      return spec.required
        ? z.string().min(1, spec.messages.required ?? 'Required').pipe(z.string().date(spec.messages.format))
        : z.string().date(spec.messages.format).optional();
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
  const obj = z.object(shape).superRefine((values, ctx) => {
    for (const [field, spec] of Object.entries(dto.fields)) {
      for (const rule of spec.rules ?? []) {
        if (rule.kind !== 'custom') continue;
        if (!evaluateCustomRule(rule, values[field], values)) {
          ctx.addIssue({ code: z.ZodIssueCode.custom, path: [field], message: rule.message, params: { errorCode: rule.errorCode } });
          break;
        }
      }
    }
    const half = dto.fields.halfDay;
    const period = dto.fields.halfDayPeriod;
    if (half?.type === 'boolean' && period?.type === 'enum' && values.halfDay === true) {
      if (values.startDate && values.endDate && values.startDate !== values.endDate) {
        ctx.addIssue({ code: z.ZodIssueCode.custom, path: ['endDate'], message: half.messages.format ?? 'Invalid half day' });
      }
      if (!values.halfDayPeriod) {
        ctx.addIssue({ code: z.ZodIssueCode.custom, path: ['halfDayPeriod'], message: period.messages.required ?? 'Required' });
      }
    }
  }) as unknown as z.ZodObject<Record<string, z.ZodTypeAny>>;
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
