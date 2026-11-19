package com.hrms.platform.param;

import com.hrms.platform.error.ApiException;
import com.hrms.platform.error.ErrorCode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Reads SYSTEM_PARAMETERS (legacy PKG_COMMON.get_parameter, error -20900 when missing). */
@Service
public class ParameterService {

    private final JdbcTemplate jdbc;

    public ParameterService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> find(String group, String code) {
        List<String> values = jdbc.queryForList(
                "SELECT param_value FROM system_parameters WHERE param_group = ? AND param_code = ?",
                String.class, group, code);
        return values.stream().findFirst();
    }

    public String get(String group, String code) {
        return find(group, code).orElseThrow(() -> new ApiException(ErrorCode.PARAMETER_NOT_FOUND,
                "Parameter not found: " + group + "." + code));
    }

    public int getInt(String group, String code, int defaultValue) {
        return find(group, code).map(String::trim).map(Integer::parseInt).orElse(defaultValue);
    }

    public BigDecimal getDecimal(String group, String code, BigDecimal defaultValue) {
        return find(group, code).map(String::trim).map(BigDecimal::new).orElse(defaultValue);
    }
}
