package com.acme.hrms.admin;

import com.acme.hrms.admin.AdminDtos.Holiday;
import com.acme.hrms.audit.AuditService.Action;
import com.acme.hrms.common.calendar.BusinessCalendar;
import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.validation.dto.admin.HolidayRequest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner of HOLIDAYS. One active row per {@code (holiday_date, location_code | company-wide)}: the
 * pre-check gives the friendly {@code -20601}, the V12 partial unique index wins races and maps to
 * the same code. {@code BusinessCalendar} (leave) reads the table per request, so writes are
 * visible immediately without any cache to invalidate.
 */
@Service
public class HolidayService {
  private static final String SELECT = "select h.* from holidays h";

  private static final RowMapper<Holiday> MAPPER =
      (rs, i) -> {
        LocalDate date = rs.getObject("holiday_date", LocalDate.class);
        return new Holiday(
            rs.getInt("holiday_id"),
            date,
            BusinessCalendar.observedDate(date),
            rs.getString("holiday_name"),
            rs.getString("location_code"),
            AdminSupport.flag(rs.getString("floating_flag")),
            AdminSupport.flag(rs.getString("active_flag")),
            rs.getString("created_by"),
            rs.getObject("created_date", LocalDateTime.class),
            null,
            null);
      };

  private final AdminSupport s;

  public HolidayService(AdminSupport s) {
    this.s = s;
  }

  public List<Holiday> list(
      @Nullable Boolean active, @Nullable Integer year, @Nullable String locationCode) {
    StringBuilder sql = new StringBuilder(SELECT).append(" where 1=1");
    sql.append(AdminSupport.activeWhere(active, "h.active_flag"));
    Map<String, Object> p = new HashMap<>();
    if (year != null) {
      sql.append(" and extract(year from h.holiday_date) = :year");
      p.put("year", year);
    }
    if (locationCode != null) {
      sql.append(" and (h.location_code is null or h.location_code = :loc)");
      p.put("loc", locationCode);
    }
    sql.append(" order by h.holiday_date, h.location_code nulls first, h.holiday_id");
    return s.jdbc().query(sql.toString(), p, MAPPER);
  }

  public Holiday get(int id) {
    List<Holiday> rows =
        s.jdbc().query(SELECT + " where h.holiday_id = :id", Map.of("id", id), MAPPER);
    if (rows.isEmpty()) {
      throw new HrmsException(ErrorCode.REFERENCE_NOT_FOUND);
    }
    return rows.get(0);
  }

  @Transactional
  public Holiday create(HolidayRequest r) {
    s.requireActiveLocation(r.getLocationCode());
    if (AdminSupport.flag(r.getActiveFlag(), true).equals("Y")) {
      requireNoActiveDuplicate(r, null);
    }
    Map<String, Object> p = params(r);
    p.put("actor", s.actor());
    p.put("now", s.now());
    int id;
    try {
      id =
          s.jdbc()
              .queryForObject(
                  """
                  insert into holidays (holiday_id, holiday_date, holiday_name, location_code,
                                        floating_flag, active_flag, created_by, created_date)
                  values (nextval('seq_holiday'), :date, :name, :loc, :floating, :active, :actor, :now)
                  returning holiday_id
                  """,
                  p,
                  Integer.class);
    } catch (DuplicateKeyException e) {
      throw duplicate(r);
    }
    Holiday created = get(id);
    s.audit("HOLIDAYS", id, Action.INSERT, null, created);
    return created;
  }

  @Transactional
  public Holiday update(int id, HolidayRequest r) {
    Holiday old = get(id);
    s.requireActiveLocation(r.getLocationCode());
    if (AdminSupport.flag(r.getActiveFlag(), true).equals("Y")) {
      requireNoActiveDuplicate(r, id);
    }
    Map<String, Object> p = params(r);
    p.put("id", id);
    try {
      s.jdbc()
          .update(
              """
              update holidays
                 set holiday_date = :date, holiday_name = :name, location_code = :loc,
                     floating_flag = :floating, active_flag = :active
               where holiday_id = :id
              """,
              p);
    } catch (DuplicateKeyException e) {
      throw duplicate(r);
    }
    Holiday updated = get(id);
    s.audit("HOLIDAYS", id, Action.UPDATE, old, updated);
    return updated;
  }

  @Transactional
  public void deactivate(int id) {
    Holiday old = get(id);
    if (!old.activeFlag()) {
      return;
    }
    s.jdbc()
        .update("update holidays set active_flag = 'N' where holiday_id = :id", Map.of("id", id));
    s.audit("HOLIDAYS", id, Action.STATUS_CHANGE, old, get(id));
  }

  private void requireNoActiveDuplicate(HolidayRequest r, @Nullable Integer excludeId) {
    Map<String, Object> p = new HashMap<>();
    p.put("date", r.getHolidayDate());
    p.put("loc", r.getLocationCode() == null ? "*" : r.getLocationCode());
    p.put("id", excludeId == null ? -1 : excludeId);
    if (s.count(
            "select count(*) from holidays where active_flag = 'Y' and holiday_date = :date"
                + " and coalesce(location_code, '*') = :loc and holiday_id <> :id",
            p)
        > 0) {
      throw duplicate(r);
    }
  }

  private static HrmsException duplicate(HolidayRequest r) {
    return AdminSupport.error(
        ErrorCode.REFERENCE_CODE_CONFLICT,
        "holidayDate",
        r.getHolidayDate() + "/" + (r.getLocationCode() == null ? "*" : r.getLocationCode()));
  }

  private static Map<String, Object> params(HolidayRequest r) {
    Map<String, Object> p = new HashMap<>();
    p.put("date", r.getHolidayDate());
    p.put("name", r.getHolidayName());
    p.put("loc", r.getLocationCode());
    p.put("floating", AdminSupport.flag(r.getFloatingFlag(), false));
    p.put("active", AdminSupport.flag(r.getActiveFlag(), true));
    return p;
  }
}
