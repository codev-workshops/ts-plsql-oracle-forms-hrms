-- ============================================================================
-- P1: schema and application role
-- Source: schema/tables/*.sql (Oracle schema HRMS) -> PostgreSQL schema hrms (D-01).
-- The migration owner owns every object. The application connects as a member of
-- the NOLOGIN role ${app_role}, which never receives DELETE on business tables (D-03).
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS hrms;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${app_role}') THEN
        CREATE ROLE ${app_role} NOLOGIN;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA hrms TO ${app_role};
