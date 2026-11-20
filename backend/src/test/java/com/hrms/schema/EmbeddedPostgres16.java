package com.hrms.schema;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

/** One embedded PostgreSQL 16 server per test JVM (no Docker); each caller gets a fresh database. */
final class EmbeddedPostgres16 {

    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static EmbeddedPostgres server;

    private EmbeddedPostgres16() {
    }

    static synchronized EmbeddedPostgres server() {
        if (server == null) {
            try {
                server = EmbeddedPostgres.builder().start();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return server;
    }

    static String newDatabaseUrl() {
        String name = "hrms_" + DATABASES.incrementAndGet();
        try (Connection c = server().getPostgresDatabase().getConnection();
             Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return server().getJdbcUrl("postgres", name);
    }
}
