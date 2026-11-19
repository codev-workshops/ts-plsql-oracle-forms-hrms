package com.hrms.platform.employee;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Maps the contract's numeric {@code locationId} to the {@code location_code} key used by the schema. */
@Service
public class LocationLookup {

    private final JdbcTemplate jdbc;

    public LocationLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> codeForId(long locationId) {
        return jdbc.queryForList("SELECT location_code FROM locations WHERE location_id = ?", String.class, locationId)
                .stream().findFirst();
    }

    public Optional<Long> idForCode(String locationCode) {
        return jdbc.queryForList("SELECT location_id FROM locations WHERE location_code = ?", Long.class, locationCode)
                .stream().findFirst();
    }
}
