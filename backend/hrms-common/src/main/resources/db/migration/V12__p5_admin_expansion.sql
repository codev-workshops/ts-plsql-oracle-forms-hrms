-- P5 §9.2 admin expansion (contracts/p5-reporting-decommission/README.md).
-- Forward-only; V1–V11 stay untouched.

-- One active holiday per (date, location | company-wide). The Java pre-check gives the
-- friendly -20601 message; this index wins races and maps to the same code.
create unique index uk_holidays_active_date_loc
    on holidays (holiday_date, coalesce(location_code, '*'))
    where active_flag = 'Y';

-- seq_role exists since V2 (start with 1, never called; roles 1–3 are seeded with literal ids).
-- Advance it so the next nextval('seq_role') is >= 1000 and strictly greater than both the
-- highest existing role_id and the sequence's prior value (is_called = false returns the set value).
select setval('seq_role',
              greatest(1000,
                       coalesce((select max(role_id) from roles), 0) + 1,
                       (select last_value + 1 from seq_role)),
              false);
