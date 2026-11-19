package com.hrms.reporting;

import static com.hrms.reporting.ReportingModels.Report;

import java.util.stream.Collectors;
import java.util.stream.Stream;

final class ReportCsv {

    private ReportCsv() { }

    static String encode(Report report) {
        StringBuilder csv = new StringBuilder();
        appendLine(csv, report.columns().stream());
        for (var row : report.rows()) {
            appendLine(csv, report.columns().stream().map(row::get));
        }
        return csv.toString();
    }

    private static void appendLine(StringBuilder csv, Stream<?> values) {
        csv.append(values.map(ReportCsv::field).collect(Collectors.joining(","))).append("\r\n");
    }

    private static String field(Object value) {
        if (value == null) {
            return "";
        }
        String content = value.toString();
        if (value instanceof String) {
            String leading = content.stripLeading();
            if (content.startsWith("\t") || content.startsWith("\r") || content.startsWith("\n")
                    || (!leading.isEmpty() && "=+-@".indexOf(leading.charAt(0)) >= 0)) {
                content = "'" + content;
            }
        }
        if (content.indexOf(',') >= 0 || content.indexOf('"') >= 0
                || content.indexOf('\r') >= 0 || content.indexOf('\n') >= 0) {
            return '"' + content.replace("\"", "\"\"") + '"';
        }
        return content;
    }
}
