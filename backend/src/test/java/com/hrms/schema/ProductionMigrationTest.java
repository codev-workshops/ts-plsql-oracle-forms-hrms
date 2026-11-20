package com.hrms.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;

/** The default (non-dev) location set applies the schema only: no seed rows. */
class ProductionMigrationTest {

    @Test
    void schemaOnlyMigrationLoadsNoData() throws Exception {
        String url = EmbeddedPostgres16.newDatabaseUrl();
        Flyway flyway = Flyway.configure()
                .dataSource(url, "postgres", "")
                .schemas("hrms")
                .defaultSchema("hrms")
                .locations("classpath:db/migration")
                .placeholders(java.util.Map.of("app_role", "hrms_app"))
                .load();
        MigrateResult result = flyway.migrate();
        assertThat(result.migrationsExecuted).isEqualTo(13); // V1-V10, V200, V400, V500
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try (Connection c = flyway.getConfiguration().getDataSource().getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT (SELECT count(*) FROM hrms.employees) + (SELECT count(*) FROM hrms.locations)")) {
            rs.next();
            assertThat(rs.getLong(1)).isZero();
        }
    }
}
