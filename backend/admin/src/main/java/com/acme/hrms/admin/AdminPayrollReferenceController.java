package com.acme.hrms.admin;

import com.acme.hrms.admin.AdminDtos.Holiday;
import com.acme.hrms.admin.AdminDtos.PayElement;
import com.acme.hrms.admin.AdminDtos.TaxBracket;
import com.acme.hrms.admin.AdminDtos.TaxLadderGap;
import com.acme.hrms.validation.dto.admin.HolidayRequest;
import com.acme.hrms.validation.dto.admin.PayElementRequest;
import com.acme.hrms.validation.dto.admin.TaxBracketRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code admin-payroll-reference} routes of the frozen P5 §9.2 amendment. */
@RestController
@Validated
public class AdminPayrollReferenceController {
  private static final String VIEW = "hasAuthority('ADMIN:VIEW')";
  private static final String EDIT = "hasAuthority('ADMIN:EDIT')";

  private final HolidayService holidays;
  private final PayElementService payElements;
  private final TaxBracketService taxBrackets;
  private final AdminValidation validation;

  public AdminPayrollReferenceController(
      HolidayService holidays,
      PayElementService payElements,
      TaxBracketService taxBrackets,
      AdminValidation validation) {
    this.holidays = holidays;
    this.payElements = payElements;
    this.taxBrackets = taxBrackets;
    this.validation = validation;
  }

  // --- holidays ----------------------------------------------------------------

  @GetMapping("/api/admin/holidays")
  @PreAuthorize(VIEW)
  public List<Holiday> listHolidays(
      @RequestParam(required = false) @Nullable Boolean active,
      @RequestParam(required = false) @Nullable @Min(1990) @Max(2100) Integer year,
      @RequestParam(required = false)
          @Nullable
          @Size(min = 1, max = 10)
          @Pattern(regexp = "^[A-Z0-9_-]+$")
          String locationCode) {
    return holidays.list(active, year, locationCode);
  }

  @PostMapping("/api/admin/holidays")
  @PreAuthorize(EDIT)
  public ResponseEntity<Holiday> createHoliday(
      @RequestBody(required = false) @Nullable HolidayRequest body) {
    Holiday h = holidays.create(validation.validate(body));
    return ResponseEntity.created(URI.create("/api/admin/holidays/" + h.holidayId())).body(h);
  }

  @GetMapping("/api/admin/holidays/{holidayId}")
  @PreAuthorize(VIEW)
  public Holiday getHoliday(@PathVariable @Min(1) int holidayId) {
    return holidays.get(holidayId);
  }

  @PutMapping("/api/admin/holidays/{holidayId}")
  @PreAuthorize(EDIT)
  public Holiday updateHoliday(
      @PathVariable @Min(1) int holidayId,
      @RequestBody(required = false) @Nullable HolidayRequest body) {
    return holidays.update(holidayId, validation.validate(body));
  }

  @DeleteMapping("/api/admin/holidays/{holidayId}")
  @PreAuthorize(EDIT)
  public ResponseEntity<Void> deactivateHoliday(@PathVariable @Min(1) int holidayId) {
    holidays.deactivate(holidayId);
    return ResponseEntity.noContent().build();
  }

  // --- pay elements ------------------------------------------------------------

  @GetMapping("/api/admin/pay-elements")
  @PreAuthorize(VIEW)
  public List<PayElement> listPayElements(
      @RequestParam(required = false) @Nullable Boolean active,
      @RequestParam(required = false)
          @Nullable
          @Pattern(regexp = "^(EARNING|DEDUCTION|TAX|BENEFIT|REIMBURSEMENT|ERROR)$")
          String elementType) {
    return payElements.list(active, elementType);
  }

  @PostMapping("/api/admin/pay-elements")
  @PreAuthorize(EDIT)
  public ResponseEntity<PayElement> createPayElement(
      @RequestBody(required = false) @Nullable PayElementRequest body) {
    PayElement e = payElements.create(validation.validate(body));
    return ResponseEntity.created(URI.create("/api/admin/pay-elements/" + e.elementId())).body(e);
  }

  @GetMapping("/api/admin/pay-elements/{elementId}")
  @PreAuthorize(VIEW)
  public PayElement getPayElement(@PathVariable @Min(0) long elementId) {
    return payElements.get(elementId);
  }

  @PutMapping("/api/admin/pay-elements/{elementId}")
  @PreAuthorize(EDIT)
  public PayElement updatePayElement(
      @PathVariable @Min(0) long elementId,
      @RequestBody(required = false) @Nullable PayElementRequest body) {
    return payElements.update(elementId, validation.validate(body));
  }

  @DeleteMapping("/api/admin/pay-elements/{elementId}")
  @PreAuthorize(EDIT)
  public ResponseEntity<Void> deactivatePayElement(@PathVariable @Min(0) long elementId) {
    payElements.deactivate(elementId);
    return ResponseEntity.noContent().build();
  }

  // --- tax brackets ------------------------------------------------------------

  @GetMapping("/api/admin/tax-brackets")
  @PreAuthorize(VIEW)
  public List<TaxBracket> listTaxBrackets(
      @RequestParam(required = false) @Nullable Boolean active,
      @RequestParam(required = false) @Nullable @Min(2000) @Max(2100) Integer taxYear,
      @RequestParam(required = false) @Nullable @Pattern(regexp = "^(FEDERAL|[A-Z]{2})$")
          String stateCode,
      @RequestParam(required = false)
          @Nullable
          @Pattern(regexp = "^(SINGLE|MARRIED_JOINT|MARRIED_SEPARATE|HEAD_OF_HOUSEHOLD|ALL)$")
          String filingStatus) {
    return taxBrackets.list(active, taxYear, stateCode, filingStatus);
  }

  @GetMapping("/api/admin/tax-brackets/ladder-gaps")
  @PreAuthorize(VIEW)
  public List<TaxLadderGap> ladderGaps(@RequestParam @Min(2000) @Max(2100) int taxYear) {
    return taxBrackets.ladderGaps(taxYear);
  }

  @PostMapping("/api/admin/tax-brackets")
  @PreAuthorize(EDIT)
  public ResponseEntity<TaxBracket> createTaxBracket(
      @RequestBody(required = false) @Nullable TaxBracketRequest body) {
    TaxBracket b = taxBrackets.create(validation.validate(body));
    return ResponseEntity.created(URI.create("/api/admin/tax-brackets/" + b.bracketId())).body(b);
  }

  @GetMapping("/api/admin/tax-brackets/{bracketId}")
  @PreAuthorize(VIEW)
  public TaxBracket getTaxBracket(@PathVariable @Min(1) long bracketId) {
    return taxBrackets.get(bracketId);
  }

  @PutMapping("/api/admin/tax-brackets/{bracketId}")
  @PreAuthorize(EDIT)
  public TaxBracket updateTaxBracket(
      @PathVariable @Min(1) long bracketId,
      @RequestBody(required = false) @Nullable TaxBracketRequest body) {
    return taxBrackets.update(bracketId, validation.validate(body));
  }

  @DeleteMapping("/api/admin/tax-brackets/{bracketId}")
  @PreAuthorize(EDIT)
  public ResponseEntity<Void> deactivateTaxBracket(@PathVariable @Min(1) long bracketId) {
    taxBrackets.deactivate(bracketId);
    return ResponseEntity.noContent().build();
  }
}
