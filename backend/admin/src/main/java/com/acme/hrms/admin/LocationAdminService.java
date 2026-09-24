package com.acme.hrms.admin;

import com.acme.hrms.admin.AdminDtos.Location;
import com.acme.hrms.audit.AuditService.Action;
import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.validation.dto.admin.LocationRequest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owner of LOCATIONS (natural key; immutable code). */
@Service
public class LocationAdminService {
  private static final String SELECT =
      """
      select l.*,
             (select count(*) from employees e
               where e.location_code = l.location_code
                 and e.employment_status = 'ACTIVE') as active_employees,
             (select count(*) from departments d
               where d.location_code = l.location_code
                 and d.active_flag = 'Y') as active_departments
        from locations l
      """;

  private static final RowMapper<Location> MAPPER =
      (rs, i) ->
          new Location(
              rs.getString("location_code"),
              rs.getString("location_name"),
              rs.getString("address_line1"),
              rs.getString("address_line2"),
              rs.getString("city"),
              rs.getString("state_province"),
              rs.getString("postal_code"),
              rs.getString("country_code"),
              rs.getString("phone_number"),
              rs.getString("timezone"),
              AdminSupport.flag(rs.getString("active_flag")),
              rs.getInt("active_employees"),
              rs.getInt("active_departments"),
              rs.getString("created_by"),
              rs.getObject("created_date", LocalDateTime.class),
              rs.getString("modified_by"),
              rs.getObject("modified_date", LocalDateTime.class));

  private final AdminSupport s;

  public LocationAdminService(AdminSupport s) {
    this.s = s;
  }

  public List<Location> list(@Nullable Boolean active) {
    return s.jdbc()
        .query(
            SELECT
                + " where 1=1"
                + AdminSupport.activeWhere(active, "l.active_flag")
                + " order by l.location_code",
            Map.of(),
            MAPPER);
  }

  public Location get(String code) {
    List<Location> rows =
        s.jdbc().query(SELECT + " where l.location_code = :c", Map.of("c", code), MAPPER);
    if (rows.isEmpty()) {
      throw new HrmsException(ErrorCode.REFERENCE_NOT_FOUND);
    }
    return rows.get(0);
  }

  @Transactional
  public Location create(LocationRequest r) {
    if (s.count(
            "select count(*) from locations where location_code = :c",
            Map.of("c", r.getLocationCode()))
        > 0) {
      throw AdminSupport.error(
          ErrorCode.REFERENCE_CODE_CONFLICT, "locationCode", r.getLocationCode());
    }
    Map<String, Object> p = params(r);
    p.put("actor", s.actor());
    p.put("now", s.now());
    s.jdbc()
        .update(
            """
            insert into locations (location_code, location_name, address_line1, address_line2, city,
                                   state_province, postal_code, country_code, phone_number, timezone,
                                   active_flag, created_by, created_date)
            values (:code, :name, :a1, :a2, :city, :state, :postal, :country, :phone, :tz,
                    :active, :actor, :now)
            """,
            p);
    Location created = get(r.getLocationCode());
    s.audit(
        "LOCATIONS", AdminSupport.recordIdOf(r.getLocationCode()), Action.INSERT, null, created);
    return created;
  }

  @Transactional
  public Location update(String code, LocationRequest r) {
    Location old = get(code);
    if (!old.locationCode().equals(r.getLocationCode())) {
      throw AdminSupport.error(
          ErrorCode.REFERENCE_CODE_CONFLICT, "locationCode", r.getLocationCode());
    }
    Map<String, Object> p = params(r);
    p.put("actor", s.actor());
    p.put("now", s.now());
    s.jdbc()
        .update(
            """
            update locations
               set location_name = :name, address_line1 = :a1, address_line2 = :a2, city = :city,
                   state_province = :state, postal_code = :postal, country_code = :country,
                   phone_number = :phone, timezone = :tz, active_flag = :active,
                   modified_by = :actor, modified_date = :now
             where location_code = :code
            """,
            p);
    Location updated = get(code);
    s.audit("LOCATIONS", AdminSupport.recordIdOf(code), Action.UPDATE, old, updated);
    return updated;
  }

  @Transactional
  public void deactivate(String code) {
    Location old = get(code);
    if (!old.activeFlag()) {
      return;
    }
    if (old.activeEmployees() > 0) {
      throw AdminSupport.error(
          ErrorCode.REFERENCE_IN_USE, null, "Location", code, old.activeEmployees(), "employees");
    }
    if (old.activeDepartments() > 0) {
      throw AdminSupport.error(
          ErrorCode.REFERENCE_IN_USE,
          null,
          "Location",
          code,
          old.activeDepartments(),
          "departments");
    }
    s.jdbc()
        .update(
            "update locations set active_flag = 'N', modified_by = :actor, modified_date = :now"
                + " where location_code = :code",
            Map.of("code", code, "actor", s.actor(), "now", s.now()));
    s.audit("LOCATIONS", AdminSupport.recordIdOf(code), Action.STATUS_CHANGE, old, get(code));
  }

  private static Map<String, Object> params(LocationRequest r) {
    Map<String, Object> p = new HashMap<>();
    p.put("code", r.getLocationCode());
    p.put("name", r.getLocationName());
    p.put("a1", r.getAddressLine1());
    p.put("a2", r.getAddressLine2());
    p.put("city", r.getCity());
    p.put("state", r.getStateProvince());
    p.put("postal", r.getPostalCode());
    p.put("country", r.getCountryCode());
    p.put("phone", r.getPhoneNumber());
    p.put(
        "tz",
        r.getTimezone() == null || r.getTimezone().isBlank()
            ? "America/New_York"
            : r.getTimezone());
    p.put("active", AdminSupport.flag(r.getActiveFlag(), true));
    return p;
  }
}
