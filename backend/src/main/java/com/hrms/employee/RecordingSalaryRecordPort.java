package com.hrms.employee;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * P5 stub of {@link SalaryRecordPort}: records every call and writes nothing (OQ-01). It never touches payroll
 * tables or code. P6 replaces this class with the payroll implementation.
 */
@Component
public class RecordingSalaryRecordPort implements SalaryRecordPort {

    private static final Logger LOG = LoggerFactory.getLogger(RecordingSalaryRecordPort.class);

    public record EndCompensation(long empId, LocalDate endDate, String actor) {
    }

    private final List<Object> calls = new CopyOnWriteArrayList<>();

    @Override
    public void createSalaryRecord(SalaryChange change) {
        LOG.info("Salary record requested (stub, not persisted): {}", change);
        calls.add(change);
    }

    @Override
    public Optional<BigDecimal> currentBaseSalary(long empId, LocalDate asOf) {
        return Optional.empty();
    }

    @Override
    public void endCompensation(long empId, LocalDate endDate, String actor) {
        LOG.info("End of compensation requested (stub, not persisted): emp {} at {}", empId, endDate);
        calls.add(new EndCompensation(empId, endDate, actor));
    }

    public List<Object> calls() {
        return List.copyOf(calls);
    }

    public void clear() {
        calls.clear();
    }
}
