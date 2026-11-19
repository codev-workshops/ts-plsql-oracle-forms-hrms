package com.hrms.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.hrms.reporting.ReportingModels.Report;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReportCsvTest {

    @Test
    void exportsColumnOrderAndEscapesTextWithoutChangingNumericCells() {
        Report report = new Report("Test", LocalDateTime.of(2026, 11, 19, 9, 0),
                List.of("text", "amount", "missing", "note"),
                List.of(Map.of("amount", new BigDecimal("-42.50"), "text", "São, \"Q\"\nOffice",
                        "note", "  =1+2"), Map.of("text", "@formula", "note", "\tHidden")));

        assertThat(ReportCsv.encode(report)).isEqualTo(
                "text,amount,missing,note\r\n"
                        + "\"São, \"\"Q\"\"\nOffice\",-42.50,,'  =1+2\r\n"
                        + "'@formula,,,'\tHidden\r\n");
    }
}
