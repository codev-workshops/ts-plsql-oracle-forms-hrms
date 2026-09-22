package com.acme.hrms.employee;

import com.acme.hrms.employee.EmployeeDtos.EmergencyContact;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

/** Sole writer of {@code emergency_contacts}; removal is {@code active_flag='N'} only. */
@Repository
public class EmergencyContactRepository {

  public record ContactWrite(
      String contactName,
      @Nullable String relationship,
      String phonePrimary,
      @Nullable String phoneSecondary,
      @Nullable String email,
      int priorityOrder,
      boolean active,
      String actor) {}

  private static final RowMapper<EmergencyContact> ROW =
      (rs, i) ->
          new EmergencyContact(
              rs.getLong("contact_id"),
              rs.getLong("emp_id"),
              rs.getString("contact_name"),
              rs.getString("relationship"),
              rs.getString("phone_primary"),
              rs.getString("phone_secondary"),
              rs.getString("email"),
              rs.getInt("priority_order"),
              "Y".equals(rs.getString("active_flag")));

  private final JdbcTemplate jdbc;

  public EmergencyContactRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<EmergencyContact> findActive(long empId) {
    return jdbc.query(
        "select * from emergency_contacts where emp_id = ? and active_flag = 'Y'"
            + " order by priority_order, contact_id",
        ROW,
        empId);
  }

  public Optional<EmergencyContact> findActive(long empId, long contactId) {
    return jdbc
        .query(
            "select * from emergency_contacts where emp_id = ? and contact_id = ?"
                + " and active_flag = 'Y'",
            ROW,
            empId,
            contactId)
        .stream()
        .findFirst();
  }

  public EmergencyContact insert(long empId, ContactWrite w) {
    Long id = jdbc.queryForObject("select nextval('seq_emergency_contact')", Long.class);
    jdbc.update(
        "insert into emergency_contacts (contact_id, emp_id, contact_name, relationship,"
            + " phone_primary, phone_secondary, email, priority_order, active_flag, created_by,"
            + " created_date) values (?,?,?,?,?,?,?,?,'Y',?, current_timestamp)",
        id,
        empId,
        w.contactName(),
        w.relationship(),
        w.phonePrimary(),
        w.phoneSecondary(),
        w.email(),
        w.priorityOrder(),
        w.actor());
    return findById(id);
  }

  public EmergencyContact update(long contactId, ContactWrite w) {
    jdbc.update(
        "update emergency_contacts set contact_name = ?, relationship = ?, phone_primary = ?,"
            + " phone_secondary = ?, email = ?, priority_order = ?, active_flag = ?,"
            + " modified_by = ?, modified_date = current_timestamp where contact_id = ?",
        w.contactName(),
        w.relationship(),
        w.phonePrimary(),
        w.phoneSecondary(),
        w.email(),
        w.priorityOrder(),
        w.active() ? "Y" : "N",
        w.actor(),
        contactId);
    return findById(contactId);
  }

  private EmergencyContact findById(long contactId) {
    return jdbc.queryForObject(
        "select * from emergency_contacts where contact_id = ?", ROW, contactId);
  }
}
