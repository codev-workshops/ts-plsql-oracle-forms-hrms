/**
 * Snapshot + format guard for the hrms-validation exporter output (VAL-03 regression guard,
 * TEST_STRATEGY.md §2.1). Contract: contracts/p0-foundation/README.md "validation-schema.json",
 * extended by contracts/p1-performance/README.md (decimal/date fields, per-DTO `module`, `modules`)
 * and contracts/p3-employee/README.md (`sensitive`, string `pattern` rules, rule `parameter`).
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
    expect(schema.modules).toEqual([
      'p0-foundation',
      'p1-performance',
      'p2-leave',
      'p3-employee',
      'p4-payroll',
      'p5-reporting-decommission',
    ]);
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
          if ('parameter' in rule) expect(schema.parameters).toHaveProperty(String(rule.parameter));
        }
        if ('sensitive' in field) expect((field as { sensitive?: unknown }).sensitive).toBe(true);
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

  it('pins the P3 employee rules (VAL-01 -20501 hire-date limit, VAL-02 server @Email, @Ssn masked, -20101 salary)', () => {
    const create = schema.dtos.EmployeeCreateRequest.fields;
    expect(create.hireDate.type).toBe('date');
    expect(create.hireDate.rules.map((r) => [r.kind, r.value, r.errorCode, r.parameter])).toEqual([
      ['custom', '90', '-20501', 'HR.MAX_FUTURE_HIRE_DAYS'],
    ]);
    expect(schema.parameters['HR.MAX_FUTURE_HIRE_DAYS']).toBe(90);
    // VAL-02: the server @Email rule (format=email) wins; no PLL regex is exported for e-mail.
    expect(create.email.format).toBe('email');
    expect(create.email).not.toHaveProperty('pattern');
    expect(create.ssn.sensitive).toBe(true);
    expect(create.ssn.pattern).toBe('^[0-9]{3}-?[0-9]{2}-?[0-9]{4}$');
    expect(create.ssn.rules.map((r) => r.kind)).toEqual(['pattern']);
    for (const phone of [create.phoneWork, create.phoneMobile]) {
      expect(phone.pattern).toBe('^(?:\\D*\\d){10,11}\\D*$');
    }
    expect(Object.keys(create)).not.toContain('empNumber');
    expect(Object.keys(schema.dtos.EmployeeUpdateRequest.fields)).not.toContain('empNumber');
    expect(Object.keys(schema.dtos.EmployeeUpdateRequest.fields)).not.toContain('employmentStatus');
    const salary = schema.dtos.SalaryChangeRequest.fields.baseSalary;
    expect(salary.type).toBe('decimal');
    expect(salary.rules.map((r) => [r.kind, r.value, r.errorCode])).toEqual([['min', 0.01, '-20101']]);
    for (const dto of [
      'EmployeeListQuery',
      'EmployeeCreateRequest',
      'EmployeeUpdateRequest',
      'EmployeeTerminateRequest',
      'EmployeeTransferRequest',
      'SalaryChangeRequest',
      'DependentRequest',
      'EmergencyContactRequest',
    ] as const) {
      expect(schema.dtos[dto].module).toBe('p3-employee');
    }
  });

  it('pins the P5 reporting/admin/integration DTOs to the contract (codes, enums, VAL-05 report filters, BUG-08 line grammar)', () => {
    const deptCode = schema.dtos.DepartmentRequest.fields.deptCode;
    expect([deptCode.required, deptCode.trim, deptCode.maxLength, deptCode.pattern]).toEqual([true, true, 20, '^[A-Z0-9_-]+$']);
    expect(schema.dtos.JobGradeRequest.fields.maxSalary.required).toBe(true);
    expect(schema.dtos.JobTitleRequest.fields.flsaStatus.values).toEqual(['EXEMPT', 'NON_EXEMPT']);
    expect(schema.dtos.LeaveTypeRequest.fields.accrualFrequency.values).toEqual(['MONTHLY', 'BIWEEKLY', 'ANNUAL']);
    expect(schema.dtos.SystemParameterRequest.fields.dataType.values).toEqual(['VARCHAR2', 'NUMBER', 'DATE', 'BOOLEAN']);
    const year = schema.dtos.CarryoverRunRequest.fields.year;
    expect([year.required, year.min, year.max]).toEqual([true, 2000, 2099]);
    expect(schema.dtos.LeaveSummaryQuery.fields.year.min).toBe(2000);
    expect(schema.dtos.PendingApprovalsQuery.fields.itemType.values).toEqual(['LEAVE', 'REVIEW']);
    expect(schema.dtos.EmployeeDirectoryQuery.fields.size.max).toBe(200);
    const hours = schema.dtos.TimeAttendanceLine.fields.hoursRegular;
    expect([hours.required, hours.min, hours.max, hours.scale]).toEqual([true, 0, 24, 2]);
    expect(schema.dtos.IntegrationFileListQuery.fields.feed.values).toEqual(['GL_JOURNAL', 'BENEFITS_FEED', 'TIME_ATTENDANCE']);
    expect(schema.dtos.AuditLogSearchQuery.fields.actionType.values).toEqual([
      'INSERT',
      'UPDATE',
      'DELETE',
      'STATUS_CHANGE',
      'LOGIN',
      'LOGOUT',
    ]);
    for (const dto of [
      'EmployeeDirectoryQuery',
      'OrgHierarchyQuery',
      'EmployeeCompensationQuery',
      'LeaveSummaryQuery',
      'PayrollLatestQuery',
      'PendingApprovalsQuery',
      'DepartmentRequest',
      'JobGradeRequest',
      'JobTitleRequest',
      'LocationRequest',
      'LeaveTypeRequest',
      'SystemParameterRequest',
      'SystemParameterUpdateRequest',
      'AccrualRunRequest',
      'CarryoverRunRequest',
      'AuditLogSearchQuery',
      'GlFeedRequest',
      'BenefitsFeedRequest',
      'TimeAttendanceLine',
      'IntegrationFileListQuery',
    ] as const) {
      expect(schema.dtos[dto].module).toBe('p5-reporting-decommission');
    }
  });

  it('pins the P4 payroll DTOs to PKG_PAYROLL / CHK_RUN_TYPE / CHK_RUN_STATUS (runType required, reversal reason required)', () => {
    expect(schema.dtos.PayrollRunCreateRequest.fields.runType.required).toBe(true);
    expect(schema.dtos.PayrollRunCreateRequest.fields.runType.values).toEqual([
      'REGULAR',
      'SUPPLEMENTAL',
      'BONUS',
      'FINAL',
    ]);
    const reason = schema.dtos.PayrollRunReverseRequest.fields.reason;
    expect([reason.required, reason.trim, reason.minLength, reason.maxLength]).toEqual([true, true, 1, 4000]);
    expect(schema.dtos.PayPeriodListQuery.fields.status.values).toEqual(['OPEN', 'PROCESSING', 'CLOSED', 'REVERSED']);
    expect(schema.dtos.PayPeriodListQuery.fields.sort.pattern).toBe(
      '^(periodStartDate|periodEndDate|payDate|periodName),(asc|desc)$',
    );
    expect(schema.dtos.PayrollRunListQuery.fields.status.values).toEqual([
      'PENDING',
      'CALCULATING',
      'CALCULATED',
      'APPROVED',
      'PAID',
      'REVERSED',
      'ERROR',
    ]);
    expect(schema.dtos.PayrollDetailListQuery.fields.status.values).toEqual(['CALCULATED', 'ERROR', 'REVERSED']);
    for (const dto of [
      'PayPeriodListQuery',
      'PayrollRunListQuery',
      'PayrollRunCreateRequest',
      'PayrollRunReverseRequest',
      'PayrollDetailListQuery',
    ] as const) {
      expect(schema.dtos[dto].module).toBe('p4-payroll');
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
