/**
 * Snapshot + format guard for the hrms-validation exporter output (VAL-03 regression guard,
 * TEST_STRATEGY.md §2.1). Contract: contracts/p0-foundation/README.md "validation-schema.json".
 *
 * This test fails when:
 *   - the exporter output shape changes (schemaVersion / envelope / field rule vocabulary), or
 *   - a rule value changes without the snapshot being intentionally updated (`vitest -u`),
 *     which is the moment a reviewer must confirm the backend Bean Validation change.
 */
import { describe, expect, it } from 'vitest';
import schema from '../validation-schema.json';

const FIELD_TYPES = ['string', 'integer', 'decimal', 'boolean', 'date', 'enum'] as const;
const RULE_KINDS = ['minLength', 'maxLength', 'pattern', 'min', 'max', 'custom'] as const;
const LEGACY_OR_FRAMEWORK_CODE = /^(-20[0-9]{3}|[A-Z][A-Z0-9_]{2,63})$/;

describe('frontend/src/generated/validation-schema.json', () => {
  it('matches the committed snapshot (byte-for-byte generator output)', () => {
    expect(schema).toMatchSnapshot();
  });

  it('carries the v1 envelope', () => {
    expect(schema.schemaVersion).toBe(1);
    expect(schema.generator).toBe('hrms-validation:exporter');
    expect(schema.generatorVersion).toMatch(/^\d+\.\d+\.\d+(-[a-z0-9.]+)?$/);
    expect(schema.sourceHash).toMatch(/^[0-9a-f]{64}$/);
    expect(schema.module).toBe('p0-foundation');
    expect(Object.keys(schema.dtos).length).toBeGreaterThan(0);
  });

  it('uses only the frozen field/rule vocabulary and sorted keys', () => {
    const dtoNames = Object.keys(schema.dtos);
    for (const dtoName of dtoNames) {
      expect(dtoName).toMatch(/^[A-Z][A-Za-z0-9]*$/);
      const dto = schema.dtos[dtoName as keyof typeof schema.dtos];
      for (const [fieldName, field] of Object.entries(dto.fields)) {
        expect(fieldName).toMatch(/^[a-z][A-Za-z0-9]*$/);
        expect(FIELD_TYPES).toContain(field.type);
        expect(typeof field.required).toBe('boolean');
        expect(field).toHaveProperty('messages');
        if (field.type === 'enum') expect(Array.isArray((field as { values?: unknown }).values)).toBe(true);
        for (const rule of (field as { rules?: Array<Record<string, unknown>> }).rules ?? []) {
          expect(RULE_KINDS).toContain(rule.kind);
          expect(rule.id).toMatch(/^[a-z][a-zA-Z0-9]*(\.[a-z][a-zA-Z0-9]*)+$/);
          expect(String(rule.errorCode)).toMatch(LEGACY_OR_FRAMEWORK_CODE);
          expect(typeof rule.message).toBe('string');
        }
      }
    }
  });

  it('pins the password policy to PKG_SECURITY (-20310, -20311, -20312 in order)', () => {
    const rules = schema.dtos.ChangePasswordRequest.fields.newPassword.rules;
    expect(rules.map((r) => r.errorCode)).toEqual(['-20310', '-20311', '-20312']);
    expect(rules[0].value).toBe(schema.parameters['SECURITY.PASSWORD_MIN_LENGTH']);
    expect(schema.dtos.ChangePasswordRequest.fields.newPassword.minLength).toBe(
      schema.parameters['SECURITY.PASSWORD_MIN_LENGTH'],
    );
  });
});
