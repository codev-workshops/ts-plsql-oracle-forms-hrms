/**
 * Snapshot + format guard for the hrms-validation exporter output (VAL-03 regression guard,
 * TEST_STRATEGY.md §2.1). Contract: contracts/p0-foundation/README.md "validation-schema.json",
 * extended by contracts/p1-performance/README.md (decimal/date fields, per-DTO `module`, `modules`).
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
    expect(schema.module).toBe('hrms');
    expect(schema.modules).toEqual(['p0-foundation', 'p1-performance', 'p2-leave']);
    expect(Object.keys(schema.dtos).length).toBeGreaterThan(0);
  });

  it('uses only the frozen field/rule vocabulary and sorted keys', () => {
    const dtoNames = Object.keys(schema.dtos);
    for (const dtoName of dtoNames) {
      expect(dtoName).toMatch(/^[A-Z][A-Za-z0-9]*$/);
      const dto = schema.dtos[dtoName as keyof typeof schema.dtos];
      expect(schema.modules).toContain(dto.module);
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

  it('pins the P1 performance bounds to PKG_PERFORMANCE (rating 1.0–5.0 / -20403, pct 0–100)', () => {
    const rating = schema.dtos.ManagerReviewRequest.fields.overallRating;
    expect(rating.type).toBe('decimal');
    expect([rating.min, rating.max, rating.scale]).toEqual([1.0, 5.0, 1]);
    expect(rating.rules.map((r) => r.errorCode)).toEqual(['-20403', '-20403']);
    for (const pct of [
      schema.dtos.GoalRequest.fields.weightPct,
      schema.dtos.GoalProgressRequest.fields.progressPct,
    ]) {
      expect(pct.type).toBe('decimal');
      expect([pct.min, pct.max]).toEqual([0, 100]);
    }
    expect(schema.dtos.GoalRequest.fields.goalCategory.values).toEqual([
      'BUSINESS',
      'DEVELOPMENT',
      'LEADERSHIP',
      'INNOVATION',
      'COMPLIANCE',
    ]);
    expect(schema.dtos.GoalProgressRequest.fields.status.values).toEqual([
      'NOT_STARTED',
      'IN_PROGRESS',
      'COMPLETED',
      'DEFERRED',
      'CANCELLED',
    ]);
    for (const dto of ['ReviewCycleRequest', 'SelfAssessmentRequest', 'AcknowledgeRequest'] as const) {
      expect(schema.dtos[dto].module).toBe('p1-performance');
    }
  });

  it('pins the P2 leave rules to PKG_LEAVE (-20210 date order, -20211 5-day past limit, AM/PM)', () => {
    const create = schema.dtos.LeaveRequestCreateRequest.fields;
    expect(create.startDate.type).toBe('date');
    expect(create.startDate.rules.map((r) => [r.kind, r.value, r.errorCode])).toEqual([
      ['custom', '5', '-20211'],
    ]);
    expect(create.endDate.rules.map((r) => [r.kind, r.value, r.errorCode])).toEqual([
      ['custom', 'startDate', '-20210'],
    ]);
    expect(create.halfDay.type).toBe('boolean');
    expect(create.halfDayPeriod.values).toEqual(['AM', 'PM']);
    expect(create.reason.maxLength).toBe(4000);
    expect(schema.dtos.LeaveRejectRequest.fields.comments.required).toBe(true);
    expect(schema.dtos.LeaveApproveRequest.fields.comments.required).toBe(false);
    expect(schema.dtos.BusinessDaysQuery.fields.end.rules[0].errorCode).toBe('-20210');
    for (const dto of [
      'LeaveRequestCreateRequest',
      'LeaveCancelRequest',
      'LeaveApproveRequest',
      'LeaveRejectRequest',
      'BusinessDaysQuery',
    ] as const) {
      expect(schema.dtos[dto].module).toBe('p2-leave');
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
