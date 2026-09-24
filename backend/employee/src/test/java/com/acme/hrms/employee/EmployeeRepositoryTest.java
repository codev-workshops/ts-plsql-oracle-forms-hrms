package com.acme.hrms.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.hrms.common.testsupport.HrmsPostgres;
import com.acme.hrms.employee.EmployeeRepository.EmployeeUpdate;
import com.acme.hrms.employee.EmployeeRepository.NewEmployee;
import com.acme.hrms.employee.EmployeeRepository.SearchFilter;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;

/** Level 1 – PostgreSQL repository tests (Testcontainers) on the frozen seed. */
class EmployeeRepositoryTest {

  private static JdbcTemplate jdbc;
  private static EmployeeRepository employees;

  @BeforeAll
  static void schema() {
    HrmsPostgres.resetSchema();
    jdbc = new JdbcTemplate(HrmsPostgres.dataSource());
    HrmsPostgres.loadFixtures(jdbc);
    employees = new EmployeeRepository(jdbc);
  }

  @BeforeEach
  void removeCreatedEmployees() {
    jdbc.update("delete from employee_history where emp_id >= 10000");
    jdbc.update("delete from employees where emp_id >= 10000");
  }

  @Test
  void flywayV6AppliesOnTheP0BaselineAndAddsVersionCommentsAndActiveEmailIndex() {
    assertThat(
            jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '6' and success",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from information_schema.columns where table_name = 'employees'"
                    + " and column_name = 'version'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from information_schema.columns where table_name ="
                    + " 'employee_history' and column_name = 'comments'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from pg_indexes where indexname = 'uk_employees_email_active'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from pg_indexes where indexname = 'uk_employees_email_lower'",
                Integer.class))
        .isZero();
  }

  @Test
  void empNumberComesFromSeqEmpNumberAsEmpDashSixDigits() {
    String first = employees.nextEmpNumber();
    String second = employees.nextEmpNumber();
    assertThat(first).matches("EMP-\\d{6}");
    assertThat(Integer.parseInt(second.substring(4)))
        .isEqualTo(Integer.parseInt(first.substring(4)) + 1);
  }

  @Test
  void seqEmpNumberRestartsAtOneHundredRightAfterTheSeededEmp000099() {
    assertThat(
            jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '9' and success",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select start_value from pg_sequences where sequencename = 'seq_emp_number'",
                Long.class))
        .isEqualTo(100L);
    assertThat(
            jdbc.queryForObject(
                "select max(emp_number) from employees where emp_number < 'EMP-000100'",
                String.class))
        .isEqualTo("EMP-000099");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from employees where emp_number >= 'EMP-001000'", Integer.class))
        .isZero();
    assertThat(Integer.parseInt(employees.nextEmpNumber().substring(4)))
        .isGreaterThanOrEqualTo(100)
        .isLessThan(1000);
  }

  @Test
  void fiftyParallelInsertsDrawDistinctSequenceNumbersWithoutDuplicateKeyErrors() throws Exception {
    int threads = 50;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Long>> created = new ArrayList<>();
    try {
      for (int i = 0; i < threads; i++) {
        String last = "RACE" + i;
        Callable<Long> task =
            () -> {
              start.await();
              return insert("P", last, null, null);
            };
        created.add(pool.submit(task));
      }
      start.countDown();
      List<Long> ids = new ArrayList<>();
      for (Future<Long> f : created) {
        ids.add(f.get(60, TimeUnit.SECONDS));
      }
      String in = String.join(",", ids.stream().map(String::valueOf).toList());
      List<String> numbers =
          jdbc.queryForList(
              "select emp_number from employees where emp_id in (" + in + ")", String.class);
      assertThat(numbers)
          .hasSize(threads)
          .doesNotHaveDuplicates()
          .allMatch(n -> n.matches("EMP-\\d{6}"));
      assertThat(
              jdbc.queryForObject(
                  "select count(distinct emp_number) from employees", Integer.class))
          .isEqualTo(jdbc.queryForObject("select count(*) from employees", Integer.class));
      jdbc.update("delete from employees where emp_id in (" + in + ")");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void recursiveChainDetectsTransitiveCyclesAndSelfManagement() {
    // seed: 1 <- 3 <- 30 <- 31 <- 32..37
    assertThat(employees.isInReportingChain(1, 32)).isTrue();
    assertThat(employees.isInReportingChain(3, 31)).isTrue();
    assertThat(employees.isInReportingChain(30, 30)).isTrue();
    assertThat(employees.isInReportingChain(32, 31)).isFalse();
    assertThat(employees.isInReportingChain(20, 31)).isFalse();
  }

  @Test
  void recursiveOrgQueryReturnsAllTransitiveReportsAndSurvivesExistingLoops() {
    assertThat(employees.reportingChainBelow(30))
        .containsExactly(31L, 32L, 33L, 34L, 35L, 36L, 37L, 99L);
    assertThat(employees.reportingChainBelow(32)).isEmpty();
    long a = insert("LOOP", "A", null, null);
    long b = insert("LOOP", "B", null, a);
    jdbc.update("update employees set manager_emp_id = ? where emp_id = ?", b, a);
    // corrupt data: the walk terminates (path guard) and reports both members once
    assertThat(employees.reportingChainBelow(a)).containsExactly(a, b);
    assertThat(employees.isInReportingChain(a, b)).isTrue();
  }

  @Test
  void blankStringsAreStoredAsSqlNullLikeOracleVarchar2() {
    long id = insert("BLANK", "TEST", "   ", null);
    assertThat(
            jdbc.queryForObject(
                "select middle_name is null and email is null and notes is null"
                    + " from employees where emp_id = ?",
                Boolean.class,
                id))
        .isTrue();
    assertThat(employees.emailInUse("", null)).isFalse();
    assertThat(employees.emailInUse("   ", null)).isFalse();
    // two employees without an e-mail never collide on the partial unique index
    insert("BLANK", "TEST2", "", null);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from employees where emp_id >= 10000 and email is null",
                Integer.class))
        .isEqualTo(2);
  }

  @Test
  void emailUniquenessIsCaseInsensitiveActiveOnlyAndExcludesTheSubject() {
    assertThat(employees.emailInUse("SARAH.CHEN@company.com", null)).isTrue();
    assertThat(employees.emailInUse("sarah.chen@company.com", 2L)).isFalse();
    // 99 is TERMINATED / active_flag = 'N' – legacy trigger only counts active rows
    assertThat(employees.emailInUse("brian.foster@company.com", null)).isFalse();
    long id = insert("DUP", "MAIL", "Brian.Foster@Company.com", null);
    assertThat(id).isPositive();
    assertThatThrownBy(() -> insert("DUP", "MAIL2", "sarah.CHEN@company.com", null))
        .isInstanceOf(DuplicateKeyException.class)
        .hasMessageContaining("uk_employees_email_active");
  }

  @Test
  void optimisticUpdateBumpsVersionAndRejectsStaleVersion() {
    long id = insert("VER", "SION", null, null);
    EmployeeUpdate u =
        new EmployeeUpdate(
            "VER",
            "",
            "SION",
            null,
            null,
            null,
            null,
            null,
            "ver.sion@x.com",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            1L,
            null,
            "FULL_TIME",
            "",
            "tester");
    assertThat(employees.update(id, 0, u)).isTrue();
    assertThat(employees.update(id, 0, u)).isFalse();
    EmployeeRow row = employees.findById(id).orElseThrow();
    assertThat(row.version()).isEqualTo(1);
    assertThat(row.middleName()).isNull();
    assertThat(row.notes()).isNull();
    assertThat(row.email()).isEqualTo("ver.sion@x.com");
  }

  @Test
  void terminateNeverDeletesTheRow() {
    long id = insert("TERM", "ROW", null, null);
    employees.terminate(id, LocalDate.of(2025, 1, 31), "VOLUNTARY", "tester");
    EmployeeRow row = employees.findById(id).orElseThrow();
    assertThat(row.terminated()).isTrue();
    assertThat(row.active()).isFalse();
    assertThat(row.employmentStatus()).isEqualTo("TERMINATED");
    assertThat(row.terminationDate()).isEqualTo(LocalDate.of(2025, 1, 31));
    assertThat(jdbc.queryForObject("select count(*) from employees", Integer.class)).isEqualTo(25);
  }

  @Test
  void searchFiltersByStatusActiveFlagAndTokens() {
    var active =
        employees.search(
            new SearchFilter(
                null, null, "chen", null, null, null, "ACTIVE", true, null, null, null, null),
            0,
            20);
    assertThat(active.totalElements()).isEqualTo(1);
    assertThat(active.content().get(0).empId()).isEqualTo(2);
    var terminated =
        employees.search(
            new SearchFilter(
                null, null, null, null, null, null, "TERMINATED", null, null, null, null, null),
            0,
            20);
    assertThat(terminated.content()).extracting(EmployeeRow::empId).containsExactly(99L);
    var excluded =
        employees.searchSummaries(
            new SearchFilter(
                null, null, null, null, null, null, "ACTIVE", true, null, null, null, 2L),
            0,
            100);
    assertThat(excluded.content()).noneMatch(s -> s.id() == 2L);
    assertThat(excluded.totalElements()).isEqualTo(22);
  }

  private static long insert(
      String first, String last, @Nullable String email, @Nullable Long manager) {
    return employees.insert(
        new NewEmployee(
            employees.nextEmpNumber(),
            first,
            "",
            last,
            null,
            null,
            null,
            null,
            null,
            email,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 1, 1),
            30,
            1,
            manager,
            "CHI",
            "FULL_TIME",
            " ",
            "tester"));
  }
}
