import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/client';
import type { Department, DepartmentRequest, JobGrade, JobGradeRequest, JobTitle, JobTitleRequest, LeaveType, LeaveTypeRequest, Location, LocationRequest } from '../../api/types';
import { humanizeCode, type FieldOption } from '../shared/schemaForm';
import { adminListKey, type ReferenceDataConfig } from './ReferenceDataTab';

const dash = (v: string | number | null | undefined) => (v === null || v === undefined || v === '' ? '—' : v);

/** Foreign-key selects for the dialogs (departments → parent / location, job titles → grade). */
export function useAdminLookups() {
  const departments = useQuery({ queryKey: adminListKey('departments', { active: true }), queryFn: () => api.admin.listDepartments({ active: true }) });
  const locations = useQuery({ queryKey: adminListKey('locations', { active: true }), queryFn: () => api.admin.listLocations({ active: true }) });
  const grades = useQuery({ queryKey: adminListKey('job-grades', { active: true }), queryFn: () => api.admin.listJobGrades({ active: true }) });
  return {
    departments: (departments.data ?? []).map((d): FieldOption => ({ value: String(d.deptId), label: `${d.deptCode} – ${d.deptName}` })),
    locations: (locations.data ?? []).map((l): FieldOption => ({ value: l.locationCode, label: `${l.locationCode} – ${l.locationName}` })),
    grades: (grades.data ?? []).map((g): FieldOption => ({ value: String(g.gradeId), label: `${g.gradeCode} – ${g.gradeName}` })),
  };
}

export type AdminLookups = ReturnType<typeof useAdminLookups>;

export function departmentsConfig(lookups: AdminLookups): ReferenceDataConfig<Department, DepartmentRequest, number> {
  return {
    id: 'departments',
    title: 'Departments',
    legacy: 'HRMS_ADMIN › DEPARTMENTS block (DEPARTMENTS, TRG_DEPT_HIERARCHY_CHECK → -20605)',
    dto: 'DepartmentRequest',
    codeField: 'deptCode',
    fields: [
      { name: 'deptCode', label: 'Code' },
      { name: 'deptName', label: 'Name' },
      { name: 'parentDeptId', label: 'Parent department', options: lookups.departments },
      { name: 'costCenter', label: 'Cost center' },
      { name: 'managerEmpId', label: 'Manager employee id' },
      { name: 'locationCode', label: 'Location', options: lookups.locations },
      { name: 'activeFlag', label: 'Active' },
    ],
    columns: [
      { key: 'deptCode', label: 'Code', render: (r) => r.deptCode },
      { key: 'deptName', label: 'Name', render: (r) => r.deptName },
      { key: 'parent', label: 'Parent', render: (r) => dash(r.parentDeptName) },
      { key: 'costCenter', label: 'Cost center', render: (r) => dash(r.costCenter) },
      { key: 'manager', label: 'Manager', render: (r) => dash(r.managerName) },
      { key: 'locationCode', label: 'Location', render: (r) => dash(r.locationCode) },
      { key: 'activeEmployees', label: 'Employees', render: (r) => dash(r.activeEmployees) },
    ],
    rowId: (r) => r.deptId,
    rowLabel: (r) => `${r.deptCode} – ${r.deptName}`,
    list: api.admin.listDepartments,
    create: api.admin.createDepartment,
    update: api.admin.updateDepartment,
    deactivate: api.admin.deactivateDepartment,
    invalidates: [['reference', 'departments']],
  };
}

export function jobGradesConfig(): ReferenceDataConfig<JobGrade, JobGradeRequest, number> {
  return {
    id: 'job-grades',
    title: 'Job grades',
    legacy: 'HRMS_ADMIN › JOB_GRADES block (CHK_SALARY_RANGE → -20603)',
    dto: 'JobGradeRequest',
    codeField: 'gradeCode',
    fields: [
      { name: 'gradeCode', label: 'Code' },
      { name: 'gradeName', label: 'Name' },
      { name: 'minSalary', label: 'Minimum salary' },
      { name: 'maxSalary', label: 'Maximum salary', hint: 'Must be ≥ minimum (checked by the server)' },
      { name: 'overtimeEligible', label: 'Overtime eligible' },
      { name: 'activeFlag', label: 'Active' },
    ],
    columns: [
      { key: 'gradeCode', label: 'Code', render: (r) => r.gradeCode },
      { key: 'gradeName', label: 'Name', render: (r) => r.gradeName },
      { key: 'range', label: 'Salary range', render: (r) => `${r.minSalary} – ${r.maxSalary}` },
      { key: 'overtimeEligible', label: 'Overtime', render: (r) => (r.overtimeEligible ? 'Yes' : 'No') },
      { key: 'activeJobTitles', label: 'Job titles', render: (r) => dash(r.activeJobTitles) },
    ],
    rowId: (r) => r.gradeId,
    rowLabel: (r) => `${r.gradeCode} – ${r.gradeName}`,
    list: api.admin.listJobGrades,
    create: api.admin.createJobGrade,
    update: api.admin.updateJobGrade,
    deactivate: api.admin.deactivateJobGrade,
    invalidates: [['reference', 'job-titles']],
  };
}

export function jobTitlesConfig(lookups: AdminLookups): ReferenceDataConfig<JobTitle, JobTitleRequest, number> {
  return {
    id: 'job-titles',
    title: 'Job titles',
    legacy: 'HRMS_ADMIN › JOB_TITLES block (inactive grade → -20604)',
    dto: 'JobTitleRequest',
    codeField: 'jobCode',
    fields: [
      { name: 'jobCode', label: 'Code' },
      { name: 'jobTitle', label: 'Title' },
      { name: 'jobFamily', label: 'Job family' },
      { name: 'gradeId', label: 'Grade', options: lookups.grades },
      { name: 'eeoCategory', label: 'EEO category' },
      { name: 'flsaStatus', label: 'FLSA status' },
      { name: 'activeFlag', label: 'Active' },
    ],
    columns: [
      { key: 'jobCode', label: 'Code', render: (r) => r.jobCode },
      { key: 'jobTitle', label: 'Title', render: (r) => r.jobTitle },
      { key: 'jobFamily', label: 'Family', render: (r) => dash(r.jobFamily) },
      { key: 'gradeCode', label: 'Grade', render: (r) => r.gradeCode },
      { key: 'flsaStatus', label: 'FLSA', render: (r) => humanizeCode(r.flsaStatus) },
      { key: 'activeEmployees', label: 'Employees', render: (r) => dash(r.activeEmployees) },
    ],
    rowId: (r) => r.jobId,
    rowLabel: (r) => `${r.jobCode} – ${r.jobTitle}`,
    list: api.admin.listJobTitles,
    create: api.admin.createJobTitle,
    update: api.admin.updateJobTitle,
    deactivate: api.admin.deactivateJobTitle,
    invalidates: [['reference', 'job-titles']],
  };
}

export function locationsConfig(): ReferenceDataConfig<Location, LocationRequest, string> {
  return {
    id: 'locations',
    title: 'Locations',
    legacy: 'HRMS_ADMIN › LOCATIONS block',
    dto: 'LocationRequest',
    codeField: 'locationCode',
    fields: [
      { name: 'locationCode', label: 'Code' },
      { name: 'locationName', label: 'Name' },
      { name: 'addressLine1', label: 'Address line 1' },
      { name: 'addressLine2', label: 'Address line 2' },
      { name: 'city', label: 'City' },
      { name: 'stateProvince', label: 'State / province' },
      { name: 'postalCode', label: 'Postal code' },
      { name: 'countryCode', label: 'Country code' },
      { name: 'phoneNumber', label: 'Phone' },
      { name: 'timezone', label: 'Time zone' },
      { name: 'activeFlag', label: 'Active' },
    ],
    columns: [
      { key: 'locationCode', label: 'Code', render: (r) => r.locationCode },
      { key: 'locationName', label: 'Name', render: (r) => r.locationName },
      { key: 'city', label: 'City', render: (r) => [r.city, r.stateProvince, r.countryCode].filter(Boolean).join(', ') || '—' },
      { key: 'timezone', label: 'Time zone', render: (r) => dash(r.timezone) },
      { key: 'activeEmployees', label: 'Employees', render: (r) => dash(r.activeEmployees) },
      { key: 'activeDepartments', label: 'Departments', render: (r) => dash(r.activeDepartments) },
    ],
    rowId: (r) => r.locationCode,
    rowLabel: (r) => `${r.locationCode} – ${r.locationName}`,
    list: api.admin.listLocations,
    create: api.admin.createLocation,
    update: api.admin.updateLocation,
    deactivate: api.admin.deactivateLocation,
    invalidates: [['reference', 'locations']],
  };
}

export function leaveTypesConfig(): ReferenceDataConfig<LeaveType, LeaveTypeRequest, number> {
  return {
    id: 'leave-types',
    title: 'Leave types',
    legacy: 'HRMS_ADMIN › LEAVE_TYPES block (carryoverMax ≤ maxBalance → -20603)',
    dto: 'LeaveTypeRequest',
    codeField: 'leaveTypeCode',
    fields: [
      { name: 'leaveTypeCode', label: 'Code' },
      { name: 'leaveTypeName', label: 'Name' },
      { name: 'paidFlag', label: 'Paid' },
      { name: 'accrualFlag', label: 'Accrues' },
      { name: 'accrualRate', label: 'Accrual rate (days)' },
      { name: 'accrualFrequency', label: 'Accrual frequency' },
      { name: 'maxBalance', label: 'Maximum balance' },
      { name: 'carryoverMax', label: 'Carryover maximum', hint: 'Must be ≤ maximum balance (checked by the server)' },
      { name: 'carryoverExpiry', label: 'Carryover expiry (days)' },
      { name: 'minTenureDays', label: 'Minimum tenure (days)' },
      { name: 'requiresApproval', label: 'Requires approval' },
      { name: 'requiresDocument', label: 'Requires document' },
      { name: 'activeFlag', label: 'Active' },
    ],
    columns: [
      { key: 'leaveTypeCode', label: 'Code', render: (r) => r.leaveTypeCode },
      { key: 'leaveTypeName', label: 'Name', render: (r) => r.leaveTypeName },
      { key: 'paid', label: 'Paid', render: (r) => (r.paidFlag ? 'Yes' : 'No') },
      { key: 'accrual', label: 'Accrual', render: (r) => (r.accrualFlag ? `${r.accrualRate ?? '—'} ${humanizeCode(r.accrualFrequency)}` : 'No') },
      { key: 'maxBalance', label: 'Max balance', render: (r) => dash(r.maxBalance) },
      { key: 'carryoverMax', label: 'Carryover max', render: (r) => dash(r.carryoverMax) },
      { key: 'requiresApproval', label: 'Approval', render: (r) => (r.requiresApproval ? 'Required' : 'No') },
      { key: 'pendingRequests', label: 'Pending', render: (r) => dash(r.pendingRequests) },
    ],
    rowId: (r) => r.leaveTypeId,
    rowLabel: (r) => `${r.leaveTypeCode} – ${r.leaveTypeName}`,
    list: api.admin.listLeaveTypes,
    create: api.admin.createLeaveType,
    update: api.admin.updateLeaveType,
    deactivate: api.admin.deactivateLeaveType,
    invalidates: [['reference', 'leave-types']],
  };
}
