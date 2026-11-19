package com.hrms.reporting;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class ReportingModels {

    private ReportingModels() { }

    record Table(List<String> columns, List<Map<String, Object>> rows) { }

    public record Report(String name, LocalDateTime generatedAt, List<String> columns,
                         List<Map<String, Object>> rows) { }
}
