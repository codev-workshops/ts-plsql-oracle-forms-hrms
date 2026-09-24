package com.acme.hrms.reference;

import com.acme.hrms.reference.ReferenceDtos.DepartmentRef;
import com.acme.hrms.reference.ReferenceDtos.JobTitleRef;
import com.acme.hrms.reference.ReferenceDtos.LeaveTypeRef;
import com.acme.hrms.reference.ReferenceDtos.LocationRef;
import java.util.List;

/**
 * Read-only view of the admin-owned reference tables (departments / job_titles / job_grades /
 * locations / leave_types) served by {@code /api/reference/*}. The admin module – the single writer
 * of those tables – provides the implementation; this module never touches them itself.
 * Implementations return the lists in the documented order and never cache: the facade computes a
 * fresh ETag per request so an admin write is visible on the next read.
 */
public interface ReferenceDataReader {

  /** Ordered by {@code dept_name, dept_id}. */
  List<DepartmentRef> departments(boolean activeOnly);

  /** Ordered by {@code job_title, job_id}. */
  List<JobTitleRef> jobTitles(boolean activeOnly);

  /** Ordered by {@code location_name, location_code}. */
  List<LocationRef> locations(boolean activeOnly);

  /** Ordered by {@code leave_type_name, leave_type_id}. */
  List<LeaveTypeRef> leaveTypes(boolean activeOnly);
}
