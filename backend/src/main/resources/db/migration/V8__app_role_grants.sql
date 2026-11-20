-- ============================================================================
-- P1: application-role privileges (D-03)
-- ${app_role} gets SELECT/INSERT/UPDATE on every table and SELECT on every view.
-- It never gets DELETE: physical deletes of business rows are forbidden (replaces
-- TRG_EMP_INSTEAD_OF_DELETE / TRG-008). Any DELETE grant (e.g. the audit retention
-- purge, PKG-A02) requires a D-03 exception amendment and its own migration.
-- ============================================================================

GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA hrms TO ${app_role};
REVOKE DELETE, TRUNCATE ON ALL TABLES IN SCHEMA hrms FROM ${app_role};
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA hrms TO ${app_role};

ALTER DEFAULT PRIVILEGES IN SCHEMA hrms GRANT SELECT, INSERT, UPDATE ON TABLES TO ${app_role};
ALTER DEFAULT PRIVILEGES IN SCHEMA hrms GRANT USAGE, SELECT ON SEQUENCES TO ${app_role};
