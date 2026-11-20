package com.hrms.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrms.schema.EmbeddedPostgres16;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Boots the full app against a fresh PostgreSQL 16 database (one per test class) with the dev seed. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ApiTestSupport.TestClock.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class ApiTestSupport {

    public static final String PASSWORD = "Seed#Pass1";

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = EmbeddedPostgres16.newDatabaseUrl();
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/seed");
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected PasswordEncoder encoder;

    @Autowired
    protected MutableClock clock;

    /** Provisions (or resets) a credential with {@link #PASSWORD} for the employee. */
    protected void provision(long empId) {
        jdbc.update("""
                INSERT INTO hrms.user_credentials (emp_id, password_hash, created_by) VALUES (?, ?, 'test')
                ON CONFLICT (emp_id) DO UPDATE SET password_hash = EXCLUDED.password_hash,
                    failed_attempts = 0, locked_until = NULL, active_flag = true
                """, empId, encoder.encode(PASSWORD));
    }

    protected ResultActions login(String username, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", username, "password", password))));
    }

    protected String tokenFor(long empId) throws Exception {
        provision(empId);
        String email = jdbc.queryForObject("SELECT email FROM hrms.employees WHERE emp_id = ?", String.class, empId);
        String body = login(email, PASSWORD).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("accessToken").asText();
    }

    protected JsonNode read(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }
}
