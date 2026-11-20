-- ============================================================================
-- P8 data migration, step 2: precheck, load, reconcile and export helpers (legacy_stage schema).
-- Migration tooling only (not business logic, D-07): dropped with legacy_stage after go-live.
--   SELECT legacy_stage.precheck();          -- issues -> legacy_stage.migration_issues; returns BLOCKING count
--   SELECT legacy_stage.load('America/New_York');  -- refuses to run with BLOCKING issues or a non-empty target
--   SELECT * FROM legacy_stage.reconcile('America/New_York');   -- row counts + checksums per table
--   SELECT legacy_stage.oracle_export_script(); SELECT legacy_stage.copy_script('/data/export');
-- ============================================================================

-- Oracle CHAR(1) flags ('Y'/'N'); also accepts true/false. Anything else is an error (no silent guess).
CREATE FUNCTION legacy_stage.to_bool(v text) RETURNS boolean
LANGUAGE plpgsql IMMUTABLE AS $$
BEGIN
    IF v IS NULL OR btrim(v) = '' THEN
        RETURN NULL;
    END IF;
    CASE upper(btrim(v))
        WHEN 'Y', 'TRUE', 'T', '1' THEN RETURN true;
        WHEN 'N', 'FALSE', 'F', '0' THEN RETURN false;
        ELSE RAISE EXCEPTION 'not a Y/N flag: %', v;
    END CASE;
END
$$;

-- Target columns of a legacy table (generated columns excluded), in table order.
CREATE FUNCTION legacy_stage.target_columns(tbl text)
RETURNS TABLE (col text, typ text, is_bool boolean, not_null boolean, def text, is_identity boolean)
LANGUAGE sql STABLE AS $$
    SELECT a.attname::text, format_type(a.atttypid, a.atttypmod), a.atttypid = 'boolean'::regtype, a.attnotnull,
           pg_get_expr(d.adbin, d.adrelid), a.attidentity <> ''
    FROM pg_attribute a
    LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
    WHERE a.attrelid = format('hrms.%I', tbl)::regclass AND a.attnum > 0 AND NOT a.attisdropped
      AND a.attgenerated = ''
    ORDER BY a.attnum
$$;

-- The expression that turns stage column s.<col> into the target value. mode: 'load' (what is inserted)
-- or 'final' (the value the column must hold after the whole load; NULL = not compared).
CREATE FUNCTION legacy_stage.col_expr(tbl text, c text, mode text) RETURNS text
LANGUAGE plpgsql STABLE AS $$
DECLARE
    r record;
    base text;
BEGIN
    SELECT * INTO r FROM legacy_stage.target_columns(tbl) t WHERE t.col = c;
    IF tbl = 'employees' AND c = 'ssn_encrypted' THEN
        RETURN CASE mode WHEN 'load' THEN 'NULL' END;              -- held in ssn_holding (OQ-P5-2)
    ELSIF tbl = 'leave_accrual_log' AND c = 'run_id' THEN
        RETURN CASE mode WHEN 'load' THEN 'NULL' END;              -- re-linked to synthesized runs
    ELSIF tbl = 'departments' AND c = 'manager_emp_id' AND mode = 'load' THEN
        RETURN 'NULL';                                              -- set after employees (circular FK)
    ELSIF tbl = 'payroll_details' AND c = 'element_id' THEN       -- DEF-P6-2
        RETURN $x$CASE WHEN s.element_type = 'ERROR' AND NULLIF(btrim(s.element_id), '') IN ('0') THEN NULL
                  ELSE NULLIF(btrim(s.element_id), '')::bigint END$x$;
    ELSIF tbl = 'performance_goals' AND c = 'review_id' THEN      -- V200 dedupe
        RETURN $x$COALESCE((SELECT d.kept_review_id FROM legacy_stage.review_dedupe d
                  WHERE d.dropped_review_id = NULLIF(btrim(s.review_id), '')::bigint),
                  NULLIF(btrim(s.review_id), '')::bigint)$x$;
    ELSIF tbl = 'performance_goals' AND c IN ('progress_pct', 'weight_pct') THEN   -- V200 checks (DEF-21)
        RETURN format('(LEAST(GREATEST(NULLIF(btrim(s.%I), %L)::numeric, 0), 100))::%s', c, '', r.typ);
    END IF;
    IF r.is_bool THEN
        base := format('legacy_stage.to_bool(s.%I)', c);
    ELSIF r.typ IN ('jsonb', 'text') OR r.typ LIKE 'character varying%' THEN
        base := format('NULLIF(s.%I, %L)::%s', c, '', r.typ);
    ELSE
        base := format('NULLIF(btrim(s.%I), %L)::%s', c, '', r.typ);
    END IF;
    -- D-01: a NULL legacy value of a NOT NULL column with a default (the nullable Oracle flags) is backfilled.
    IF r.not_null AND r.def IS NOT NULL AND NOT r.is_identity THEN
        -- the clock defaults become the recorded load time, so the reconciliation can recompute them
        RETURN format('COALESCE(%s, %s)', base, replace(replace(r.def, 'CURRENT_TIMESTAMP',
                      '(SELECT loaded_at FROM legacy_stage.load_run)'), 'CURRENT_DATE',
                      '(SELECT loaded_at FROM legacy_stage.load_run)::date'));
    END IF;
    RETURN base;
END
$$;

CREATE FUNCTION legacy_stage.issue(sev text, chk text, tbl text, n bigint, msg text) RETURNS void
LANGUAGE sql AS $$
    INSERT INTO legacy_stage.migration_issues (severity, check_name, table_name, detail, row_count)
    SELECT sev, chk, tbl, msg, n WHERE n > 0
$$;

-- Reports every known legacy-data problem. Returns the number of BLOCKING issues (load refuses to run).
CREATE FUNCTION legacy_stage.precheck() RETURNS integer
LANGUAGE plpgsql AS $$
DECLARE
    t record;
    c record;
    fk record;
    n bigint;
    pk text;
BEGIN
    TRUNCATE legacy_stage.migration_issues;
    FOR t IN SELECT table_name FROM legacy_stage.legacy_tables ORDER BY load_order LOOP
        -- duplicate primary keys
        SELECT a.attname INTO pk FROM pg_index i
        JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0]
        WHERE i.indrelid = format('hrms.%I', t.table_name)::regclass AND i.indisprimary;
        EXECUTE format('SELECT count(*) FROM (SELECT btrim(%I) FROM legacy_stage.%I GROUP BY 1 HAVING count(*) > 1) x',
                       pk, t.table_name) INTO n;
        PERFORM legacy_stage.issue('BLOCKING', 'DUPLICATE_PK', t.table_name, n, format('duplicate %s values', pk));
        FOR c IN SELECT * FROM legacy_stage.target_columns(t.table_name) LOOP
            -- NOT NULL without a default (a default is backfilled)
            IF c.not_null AND (c.def IS NULL OR c.is_identity) THEN
                EXECUTE format('SELECT count(*) FROM legacy_stage.%I WHERE NULLIF(btrim(%I), %L) IS NULL',
                               t.table_name, c.col, '') INTO n;
                PERFORM legacy_stage.issue('BLOCKING', 'NOT_NULL', t.table_name, n, format('%s is empty', c.col));
            ELSIF c.not_null AND c.is_bool THEN
                EXECUTE format('SELECT count(*) FROM legacy_stage.%I WHERE NULLIF(btrim(%I), %L) IS NULL',
                               t.table_name, c.col, '') INTO n;
                PERFORM legacy_stage.issue('INFO', 'FLAG_BACKFILL', t.table_name, n,
                        format('NULL %s backfilled with %s (D-01)', c.col, c.def));
            END IF;
            IF c.is_bool THEN
                EXECUTE format($q$SELECT count(*) FROM legacy_stage.%I WHERE NULLIF(btrim(%I), '') IS NOT NULL
                               AND upper(btrim(%I)) NOT IN ('Y', 'N', 'TRUE', 'FALSE', 'T', 'F', '1', '0')$q$,
                               t.table_name, c.col, c.col) INTO n;
                PERFORM legacy_stage.issue('BLOCKING', 'BAD_FLAG', t.table_name, n, format('%s is not Y/N', c.col));
            END IF;
        END LOOP;
    END LOOP;

    -- FK orphans, including the FKs P1 declared that Oracle only documented
    FOR fk IN
        SELECT cl.relname::text AS child, ca.attname::text AS ccol, pl.relname::text AS parent, pa.attname::text AS pcol,
               co.conname::text AS name
        FROM pg_constraint co
        JOIN pg_class cl ON cl.oid = co.conrelid JOIN pg_class pl ON pl.oid = co.confrelid
        JOIN pg_attribute ca ON ca.attrelid = co.conrelid AND ca.attnum = co.conkey[1]
        JOIN pg_attribute pa ON pa.attrelid = co.confrelid AND pa.attnum = co.confkey[1]
        WHERE co.contype = 'f' AND cardinality(co.conkey) = 1
          AND cl.relnamespace = 'hrms'::regnamespace
          AND cl.relname IN (SELECT table_name FROM legacy_stage.legacy_tables)
          AND pl.relname IN (SELECT table_name FROM legacy_stage.legacy_tables)
    LOOP
        EXECUTE format($q$SELECT count(*) FROM legacy_stage.%I s WHERE NULLIF(btrim(s.%I), '') IS NOT NULL
                       AND NOT (%s) AND NOT EXISTS (SELECT 1 FROM legacy_stage.%I p WHERE btrim(p.%I) = btrim(s.%I))$q$,
                       fk.child, fk.ccol,
                       CASE WHEN fk.child = 'payroll_details' AND fk.ccol = 'element_id'
                            THEN $x$COALESCE(s.element_type = 'ERROR' AND btrim(s.element_id) = '0', false)$x$
                            ELSE 'false' END,
                       fk.parent, fk.pcol, fk.ccol) INTO n;
        PERFORM legacy_stage.issue('BLOCKING', 'FK_ORPHAN', fk.child, n,
                format('%s.%s has no matching %s.%s (%s)', fk.child, fk.ccol, fk.parent, fk.pcol, fk.name));
    END LOOP;

    -- DEF-24: the tightened CHECKs (Oracle's IN (..., NULL) accepted anything)
    SELECT count(*) INTO n FROM legacy_stage.leave_types
    WHERE NULLIF(btrim(accrual_frequency), '') IS NOT NULL AND accrual_frequency NOT IN ('MONTHLY', 'BIWEEKLY', 'ANNUAL');
    PERFORM legacy_stage.issue('BLOCKING', 'DEF-24', 'leave_types', n, 'accrual_frequency outside MONTHLY/BIWEEKLY/ANNUAL');
    SELECT count(*) INTO n FROM legacy_stage.leave_requests
    WHERE NULLIF(btrim(half_day_period), '') IS NOT NULL AND half_day_period NOT IN ('AM', 'PM');
    PERFORM legacy_stage.issue('BLOCKING', 'DEF-24', 'leave_requests', n, 'half_day_period outside AM/PM');

    -- OQ-06 / TRG-004: the partial unique index allows one ACTIVE row per email
    SELECT count(*) INTO n FROM (
        SELECT lower(btrim(email)) FROM legacy_stage.employees
        WHERE COALESCE(legacy_stage.to_bool(active_flag), true) AND NULLIF(btrim(email), '') IS NOT NULL
        GROUP BY 1 HAVING count(*) > 1) x;
    PERFORM legacy_stage.issue('BLOCKING', 'OQ-06', 'employees', n,
            'emails shared by more than one active employee (deactivate or re-address all but the lowest emp_id)');

    -- DEF-P6-3: V400 unique (pay_frequency, period_start_date)
    SELECT count(*) INTO n FROM (SELECT pay_frequency, btrim(period_start_date) FROM legacy_stage.pay_periods
                                 GROUP BY 1, 2 HAVING count(*) > 1) x;
    PERFORM legacy_stage.issue('BLOCKING', 'DEF-P6-3', 'pay_periods', n, 'duplicate pay periods (frequency, start date)');

    -- V200 NOT VALID checks still apply to newly inserted rows
    SELECT count(*) INTO n FROM legacy_stage.review_cycles
    WHERE NULLIF(btrim(end_date), '')::date < NULLIF(btrim(start_date), '')::date;
    PERFORM legacy_stage.issue('BLOCKING', 'V200', 'review_cycles', n, 'end_date before start_date');
    SELECT count(*) INTO n FROM legacy_stage.performance_goals
    WHERE NULLIF(btrim(progress_pct), '')::numeric NOT BETWEEN 0 AND 100
       OR NULLIF(btrim(weight_pct), '')::numeric NOT BETWEEN 0 AND 100;
    PERFORM legacy_stage.issue('WARN', 'V200', 'performance_goals', n, 'progress_pct/weight_pct clamped to 0-100 (DEF-21)');
    SELECT count(*) - count(DISTINCT (btrim(cycle_id), btrim(emp_id), review_type)) INTO n FROM legacy_stage.performance_reviews;
    PERFORM legacy_stage.issue('INFO', 'V200', 'performance_reviews', n,
            'duplicate reviews (cycle, employee, type) dropped; their goals move to the kept review');

    -- audit payloads must be JSON objects (D-08)
    SELECT count(*) INTO n FROM legacy_stage.audit_log
    WHERE (NULLIF(old_values, '') IS NOT NULL AND NOT (old_values IS JSON OBJECT))
       OR (NULLIF(new_values, '') IS NOT NULL AND NOT (new_values IS JSON OBJECT));
    PERFORM legacy_stage.issue('BLOCKING', 'D-08', 'audit_log', n, 'old_values/new_values not a JSON object');

    -- Informational / follow-up
    SELECT count(*) INTO n FROM legacy_stage.employees
    WHERE NULLIF(btrim(email), '') IS NOT NULL AND email !~ '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$';
    PERFORM legacy_stage.issue('WARN', 'DEF-04', 'employees', n,
            'emails the server validator rejects (loaded as-is; the next edit must fix them)');
    SELECT count(*) INTO n FROM legacy_stage.holidays h
    WHERE COALESCE(legacy_stage.to_bool(h.active_flag), true)
      AND EXTRACT(ISODOW FROM NULLIF(btrim(h.holiday_date), '')::date) IN (6, 7)
      AND NOT EXISTS (SELECT 1 FROM legacy_stage.holidays o
                      WHERE COALESCE(legacy_stage.to_bool(o.active_flag), true)
                        AND COALESCE(o.location_code, '') = COALESCE(h.location_code, '')
                        AND NULLIF(btrim(o.holiday_date), '')::date = CASE EXTRACT(ISODOW FROM h.holiday_date::date)
                            WHEN 6 THEN h.holiday_date::date - 1 ELSE h.holiday_date::date + 1 END);
    PERFORM legacy_stage.issue('WARN', 'DEF-02', 'holidays', n,
            'weekend holidays without an observed-date row (add one; business days match exact dates)');
    SELECT count(*) INTO n FROM legacy_stage.employees WHERE NULLIF(btrim(ssn_encrypted), '') IS NOT NULL;
    PERFORM legacy_stage.issue('WARN', 'OQ-P5-2', 'employees', n,
            'SSN ciphertext held in legacy_stage.ssn_holding, not loaded; re-encrypt with the external key');
    SELECT count(*) INTO n FROM (SELECT 1 FROM legacy_stage.leave_accrual_log
        GROUP BY date_trunc('month', NULLIF(btrim(accrual_date), '')::date), btrim(emp_id), btrim(leave_type_id)
        HAVING count(*) > 1) x;
    PERFORM legacy_stage.issue('WARN', 'PKG-L04', 'leave_accrual_log', n,
            'legacy double accruals (same month, employee, type); kept, only the first is linked to the month''s run');
    SELECT count(*) INTO n FROM legacy_stage.tax_brackets;
    PERFORM legacy_stage.issue('INFO', 'DEF-05', 'tax_brackets', n,
            'not loaded: V400 tax_brackets/payroll_tax_parameters are authoritative (legacy code never read the table)');
    SELECT count(*) INTO n FROM legacy_stage.user_sessions WHERE session_status = 'ACTIVE';
    PERFORM legacy_stage.issue('INFO', 'D-06', 'user_sessions', n,
            'legacy ACTIVE sessions are loaded as CLOSED (they have no token)');

    SELECT count(*) INTO n FROM legacy_stage.migration_issues WHERE severity = 'BLOCKING';
    RETURN n;
END
$$;

CREATE FUNCTION legacy_stage.select_list(tbl text, mode text) RETURNS TABLE (cols text, exprs text)
LANGUAGE sql STABLE AS $$
    SELECT string_agg(format('%I', t.col), ', ' ORDER BY ord), string_agg(e, ', ' ORDER BY ord)
    FROM (SELECT t.col, legacy_stage.col_expr(tbl, t.col, mode) AS e, row_number() OVER () AS ord
          FROM legacy_stage.target_columns(tbl) t) t
    WHERE e IS NOT NULL
$$;

-- Loads the stage into an empty hrms schema in one transaction. Timestamps without a zone (Oracle DATE)
-- are read in legacy_tz.
CREATE FUNCTION legacy_stage.load(legacy_tz text DEFAULT 'UTC') RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE
    t record;
    sel record;
    n bigint;
    total bigint := 0;
    r record;
BEGIN
    IF EXISTS (SELECT 1 FROM hrms.employees) THEN
        RAISE EXCEPTION 'target hrms schema is not empty: load into a fresh database (flyway migrate, production locations)';
    END IF;
    IF legacy_stage.precheck() > 0 THEN
        RAISE EXCEPTION 'precheck found BLOCKING issues; see legacy_stage.migration_issues';
    END IF;
    PERFORM set_config('TimeZone', legacy_tz, true);
    TRUNCATE legacy_stage.review_dedupe, legacy_stage.ssn_holding, legacy_stage.load_log, legacy_stage.load_run;
    INSERT INTO legacy_stage.load_run VALUES (now());

    INSERT INTO legacy_stage.review_dedupe (dropped_review_id, kept_review_id)
    SELECT review_id, kept FROM (
        SELECT btrim(review_id)::bigint AS review_id,
               first_value(btrim(review_id)::bigint) OVER w AS kept, row_number() OVER w AS rn
        FROM legacy_stage.performance_reviews
        WINDOW w AS (PARTITION BY btrim(cycle_id), btrim(emp_id), review_type
                     ORDER BY (NULLIF(btrim(overall_rating), '') IS NOT NULL) DESC,
                              (status IS DISTINCT FROM 'NOT_STARTED') DESC, btrim(review_id)::bigint)) x
    WHERE rn > 1;
    INSERT INTO legacy_stage.ssn_holding (emp_id, ssn_encrypted)
    SELECT btrim(emp_id)::bigint, ssn_encrypted FROM legacy_stage.employees WHERE NULLIF(btrim(ssn_encrypted), '') IS NOT NULL;

    FOR t IN SELECT table_name FROM legacy_stage.legacy_tables WHERE table_name <> 'tax_brackets' ORDER BY load_order LOOP
        SELECT * INTO sel FROM legacy_stage.select_list(t.table_name, 'load');
        EXECUTE format('INSERT INTO hrms.%I (%s) SELECT %s FROM legacy_stage.%I s %s', t.table_name, sel.cols, sel.exprs,
                       t.table_name, CASE t.table_name WHEN 'performance_reviews' THEN
                       'WHERE NOT EXISTS (SELECT 1 FROM legacy_stage.review_dedupe d WHERE d.dropped_review_id = btrim(s.review_id)::bigint)'
                       ELSE '' END);
        GET DIAGNOSTICS n = ROW_COUNT;
        INSERT INTO legacy_stage.load_log VALUES (t.table_name, n);
        total := total + n;
        IF t.table_name = 'employees' THEN
            UPDATE hrms.departments d SET manager_emp_id = btrim(s.manager_emp_id)::bigint
            FROM legacy_stage.departments s
            WHERE d.dept_id = btrim(s.dept_id)::bigint AND NULLIF(btrim(s.manager_emp_id), '') IS NOT NULL;
        END IF;
    END LOOP;

    UPDATE hrms.user_sessions SET session_status = 'CLOSED', logout_time = COALESCE(logout_time, login_time)
    WHERE session_status = 'ACTIVE';

    -- P3 accrual idempotency: months the legacy job accrued become runs, so they are never accrued again
    INSERT INTO hrms.leave_accrual_runs (accrual_period, accrual_date, employees_processed, entries_created,
                                         total_accrued, created_by)
    SELECT date_trunc('month', accrual_date)::date, min(accrual_date), count(DISTINCT emp_id), count(*),
           sum(accrual_amount), 'system:migration'
    FROM hrms.leave_accrual_log GROUP BY 1;
    UPDATE hrms.leave_accrual_log l SET run_id = x.run_id
    FROM (SELECT l2.accrual_id, r2.run_id,
                 row_number() OVER (PARTITION BY r2.run_id, l2.emp_id, l2.leave_type_id ORDER BY l2.accrual_id) AS rn
          FROM hrms.leave_accrual_log l2
          JOIN hrms.leave_accrual_runs r2 ON r2.accrual_period = date_trunc('month', l2.accrual_date)::date) x
    WHERE l.accrual_id = x.accrual_id AND x.rn = 1;

    -- PKG-L05 idempotency: years the legacy job already carried over are never carried again (DEF-03)
    INSERT INTO hrms.leave_carryover_runs (run_type, run_key, from_year, balances_processed, total_days, created_by)
    SELECT 'CARRYOVER', make_date(calendar_year, 1, 1), calendar_year - 1, count(*), sum(carryover_from_prev),
           'system:migration'
    FROM hrms.leave_balances WHERE carryover_from_prev > 0 OR carryover_expiry_dt IS NOT NULL
    GROUP BY calendar_year;

    -- D-02: identities and the EMP- number sequence continue above the migrated ids
    FOR r IN
        SELECT c.table_name, c.column_name, pg_get_serial_sequence(format('hrms.%I', c.table_name), c.column_name) AS seq,
               s.seqstart
        FROM information_schema.columns c
        JOIN pg_sequence s ON s.seqrelid = pg_get_serial_sequence(format('hrms.%I', c.table_name), c.column_name)::regclass
        WHERE c.table_schema = 'hrms' AND c.is_identity = 'YES'
    LOOP
        EXECUTE format('SELECT setval(%L, GREATEST(COALESCE((SELECT max(%I) FROM hrms.%I), 0) + 1, %s), false)',
                       r.seq, r.column_name, r.table_name, r.seqstart);
    END LOOP;
    PERFORM setval('hrms.seq_emp_number',
                   GREATEST((SELECT COALESCE(max(substring(emp_number FROM '^EMP-(\d{6})$')::bigint), 0) + 1
                             FROM hrms.employees), 1000), false);
    RETURN total;
END
$$;

-- Row counts and checksums: the stage, transformed with the load rules, against the loaded tables.
CREATE FUNCTION legacy_stage.reconcile(legacy_tz text DEFAULT 'UTC')
RETURNS TABLE (table_name text, stage_rows bigint, expected_rows bigint, target_rows bigint,
               stage_checksum text, target_checksum text, status text)
LANGUAGE plpgsql AS $$
DECLARE
    t record;
    c record;
    e text;
    stage_list text;
    tgt text;
    filter text;
BEGIN
    PERFORM set_config('TimeZone', legacy_tz, true);
    FOR t IN SELECT l.table_name FROM legacy_stage.legacy_tables l ORDER BY l.load_order LOOP
        table_name := t.table_name;
        EXECUTE format('SELECT count(*) FROM legacy_stage.%I', t.table_name) INTO stage_rows;
        EXECUTE format('SELECT count(*) FROM hrms.%I', t.table_name) INTO target_rows;
        IF t.table_name = 'tax_brackets' THEN
            expected_rows := NULL; stage_checksum := NULL; target_checksum := NULL; status := 'SKIPPED (V400)';
            RETURN NEXT;
            CONTINUE;
        END IF;
        filter := CASE t.table_name WHEN 'performance_reviews' THEN
            'WHERE NOT EXISTS (SELECT 1 FROM legacy_stage.review_dedupe d WHERE d.dropped_review_id = btrim(s.review_id)::bigint)'
            ELSE '' END;
        stage_list := NULL;
        tgt := NULL;
        FOR c IN SELECT tc.col FROM legacy_stage.target_columns(t.table_name) tc LOOP
            e := legacy_stage.col_expr(t.table_name, c.col, 'final');
            CONTINUE WHEN e IS NULL;
            IF t.table_name = 'user_sessions' AND c.col = 'session_status' THEN  -- the load closes ACTIVE sessions
                e := $x$CASE WHEN s.session_status = 'ACTIVE' THEN 'CLOSED' ELSE NULLIF(s.session_status, '') END$x$;
            ELSIF t.table_name = 'user_sessions' AND c.col = 'logout_time' THEN
                e := $x$COALESCE(NULLIF(btrim(s.logout_time), ''),
                        CASE WHEN s.session_status = 'ACTIVE' THEN NULLIF(btrim(s.login_time), '') END)::timestamptz$x$;
            END IF;
            stage_list := concat_ws(', ', stage_list, format('COALESCE((%s)::text, %L)', e, '<null>'));
            tgt := concat_ws(', ', tgt, format('COALESCE(%I::text, %L)', c.col, '<null>'));
        END LOOP;
        EXECUTE format('SELECT count(*) FROM legacy_stage.%I s %s', t.table_name, filter) INTO expected_rows;
        EXECUTE format($q$SELECT md5(COALESCE(string_agg(r, E'\n' ORDER BY r), '')) FROM (
                         SELECT concat_ws('|', %s) AS r FROM legacy_stage.%I s %s) x$q$, stage_list, t.table_name, filter)
            INTO stage_checksum;
        EXECUTE format($q$SELECT md5(COALESCE(string_agg(r, E'\n' ORDER BY r), '')) FROM (
                         SELECT concat_ws('|', %s) AS r FROM hrms.%I) x$q$, tgt, t.table_name)
            INTO target_checksum;
        status := CASE WHEN expected_rows = target_rows AND stage_checksum = target_checksum THEN 'MATCH' ELSE 'MISMATCH' END;
        RETURN NEXT;
    END LOOP;
END
$$;

-- The SQL*Plus export for the legacy database: one CSV per table, columns in stage order, flags as Y/N,
-- dates ISO-8601. Columns that exist only in PostgreSQL are exported as NULL; BLOBs are exported separately.
CREATE FUNCTION legacy_stage.oracle_export_script() RETURNS text
LANGUAGE sql STABLE AS $$
    SELECT 'SET MARKUP CSV ON QUOTE ON' || E'\nSET FEEDBACK OFF\nSET TERMOUT OFF\nSET PAGESIZE 0 EMBEDDED ON\n'
        || string_agg(format(E'SPOOL %s.csv\nSELECT %s FROM HRMS.%s;\nSPOOL OFF', l.table_name,
               (SELECT string_agg(CASE
                    WHEN (l.table_name, t.col) IN (('user_sessions', 'token_hash'), ('user_sessions', 'expires_at'))
                        OR t.typ = 'bytea' THEN 'NULL AS ' || upper(t.col)
                    WHEN t.typ = 'date' THEN format('TO_CHAR(%s, ''YYYY-MM-DD'') AS %s', upper(t.col), upper(t.col))
                    WHEN t.typ = 'timestamp with time zone'
                        THEN format('TO_CHAR(%s, ''YYYY-MM-DD"T"HH24:MI:SS'') AS %s', upper(t.col), upper(t.col))
                    ELSE upper(t.col) END, ', ')
                FROM legacy_stage.target_columns(l.table_name) t),
               upper(l.table_name)), E'\n' ORDER BY l.load_order)
    FROM legacy_stage.legacy_tables l
$$;

CREATE FUNCTION legacy_stage.copy_script(dir text) RETURNS text
LANGUAGE sql STABLE AS $$
    SELECT string_agg(format('\copy legacy_stage.%I FROM %L WITH (FORMAT csv, HEADER true)', table_name,
                             dir || '/' || table_name || '.csv'), E'\n' ORDER BY load_order)
    FROM legacy_stage.legacy_tables
$$;
