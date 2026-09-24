package com.acme.hrms.auth;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.Slice;
import java.util.Set;

/**
 * Module ownership rules from MODERNIZATION_BLUEPRINT.md / CUTOVER_PLAN.md §8 /
 * contracts/p4-payroll: one writer per table, salary-module owns SALARY_RECORDS, employee-module
 * owns bank accounts, payroll reads both only through their public projections.
 */
@AnalyzeClasses(packages = "com.acme.hrms", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryArchTest {

  @ArchTest
  static final ArchRule salary_records_repository_is_salary_module_internal =
      noClasses()
          .that()
          .resideOutsideOfPackage("com.acme.hrms.salary..")
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("com.acme.hrms.salary.SalaryRecordRepository")
          .because("only salary-module reads or writes SALARY_RECORDS; P4 uses SalaryAsOfReader");

  @ArchTest
  static final ArchRule payroll_uses_only_the_public_salary_boundary =
      noClasses()
          .that()
          .resideInAPackage("com.acme.hrms.payroll..")
          .should()
          .dependOnClassesThat(
              resideInAPackage("com.acme.hrms.salary..")
                  .and(
                      not(
                          nameMatching(
                              "com\\.acme\\.hrms\\.salary\\.(SalaryAsOfReader|SalaryDtos(\\$.*)?)"))))
          .because("payroll may only read salary as-of period end via SalaryAsOfReader");

  @ArchTest
  static final ArchRule payroll_reads_bank_accounts_only_via_masked_projection =
      noClasses()
          .that()
          .resideInAPackage("com.acme.hrms.payroll..")
          .should()
          .dependOnClassesThat(
              resideInAPackage("com.acme.hrms.employee..")
                  .and(
                      not(
                          nameMatching(
                              "com\\.acme\\.hrms\\.employee\\.EmployeeBankAccountProjection(\\$.*)?"))))
          .because("pay register export gets masked bank accounts from employee-module only");

  @ArchTest
  static final ArchRule no_cycles_between_employee_salary_and_payroll =
      slices()
          .matching("com.acme.hrms.(*)..")
          .namingSlices("$1")
          .that(
              DescribedPredicate.describe(
                  "are employee, salary or payroll",
                  (Slice s) ->
                      Set.of("employee", "salary", "payroll").contains(s.getDescription())))
          .should()
          .beFreeOfCycles();

  @ArchTest
  static final ArchRule salary_and_employee_do_not_depend_on_payroll =
      noClasses()
          .that()
          .resideInAnyPackage("com.acme.hrms.salary..", "com.acme.hrms.employee..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.acme.hrms.payroll..")
          .because("payroll is downstream of salary-module and employee-module");

  @ArchTest
  static final ArchRule admin_never_depends_on_auth =
      noClasses()
          .that()
          .resideInAPackage("com.acme.hrms.admin..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.acme.hrms.auth..")
          .because(
              "auth depends on admin (P5 role management lives in auth); admin -> auth is a cycle");

  @ArchTest
  static final ArchRule reference_facade_reads_admin_tables_only_through_the_reader_interface =
      noClasses()
          .that()
          .resideInAPackage("com.acme.hrms.reference..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.acme.hrms.admin..")
          .because(
              "admin owns reads and writes of the reference tables and implements"
                  + " ReferenceDataReader; reference must not reach into admin");

  @ArchTest
  static final ArchRule reference_module_has_no_jdbc_of_its_own_for_admin_tables =
      noClasses()
          .that()
          .resideInAPackage("com.acme.hrms.reference..")
          .and()
          .haveSimpleNameNotContaining("EmployeeLookup")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("org.springframework.jdbc..")
          .because("only the employee picker keeps its own query; reference data comes via admin");
}
