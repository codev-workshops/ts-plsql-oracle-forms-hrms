package com.acme.hrms.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.hrms.common.error.ErrorCode;
import com.acme.hrms.common.error.HrmsException;
import com.acme.hrms.validation.dto.admin.HolidayRequest;
import com.acme.hrms.validation.dto.admin.PayElementRequest;
import com.acme.hrms.validation.dto.admin.TaxBracketRequest;
import com.acme.hrms.validation.dto.admin.TaxRateDeserializer;
import com.acme.hrms.validation.dto.auth.RoleRequest;
import com.acme.hrms.validation.dto.auth.UserRolesRequest;
import com.acme.hrms.validation.dto.auth.UserStatusRequest;
import com.acme.hrms.validation.dto.employee.MoneyDeserializer;
import com.acme.hrms.validation.dto.employee.StrictRequest;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Wire-type pins of the P5 §9.2 admin request bodies (contracts/p5-reporting-decommission). */
class P5AdminRequestWireTest {

  private final ObjectMapper lenient =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  @Test
  void everyP5AdminRequestBodyIsStrict() {
    assertThat(HolidayRequest.class).isAssignableTo(StrictRequest.class);
    assertThat(PayElementRequest.class).isAssignableTo(StrictRequest.class);
    assertThat(TaxBracketRequest.class).isAssignableTo(StrictRequest.class);
    assertThat(RoleRequest.class).isAssignableTo(StrictRequest.class);
    assertThat(UserRolesRequest.class).isAssignableTo(StrictRequest.class);
    assertThat(UserStatusRequest.class).isAssignableTo(StrictRequest.class);
  }

  @Test
  void payElementDecimalsBindOnlyTwoDecimalStrings() throws Exception {
    PayElementRequest ok =
        lenient.readValue(
            "{\"elementCode\":\"BONUS\",\"defaultAmount\":\"150.00\",\"defaultPercentage\":\"12.50\"}",
            PayElementRequest.class);
    assertThat(ok.getDefaultAmount()).isEqualByComparingTo("150.00");
    assertThat(ok.getDefaultPercentage()).isEqualByComparingTo("12.50");
    assertThat(ok.malformedProperties()).isEmpty();

    for (String wire : new String[] {"150", "150.5", "\"150\"", "\"150.5\"", "\"1.500\"", "{}"}) {
      PayElementRequest body =
          lenient.readValue(
              "{\"elementCode\":\"BONUS\",\"defaultAmount\":" + wire + ",\"elementName\":\"B\"}",
              PayElementRequest.class);
      assertThat(body.getDefaultAmount()).as(wire).isNull();
      assertThat(body.getElementName()).as(wire).isEqualTo("B");
      assertThat(body.unknownProperties()).as(wire).isEmpty();
      assertThat(body.malformedProperties()).as(wire).containsExactly("defaultAmount");
      assertThatThrownBy(body::requireNoMalformedProperties)
          .isInstanceOfSatisfying(
              HrmsException.class,
              e -> {
                assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                assertThat(e.field()).isEqualTo("defaultAmount");
                assertThat(e.details())
                    .extracting(d -> d.field() + ":" + d.code() + ":" + d.message())
                    .containsExactly("defaultAmount:InvalidFormat:" + MoneyDeserializer.MESSAGE);
              });
    }
  }

  @Test
  void taxBracketMoneyAndRateBindOnlyContractStrings() throws Exception {
    TaxBracketRequest ok =
        lenient.readValue(
            "{\"taxYear\":2026,\"bracketMin\":\"0.00\",\"bracketMax\":\"11000.00\","
                + "\"taxRate\":\"0.1\",\"baseTax\":\"0.00\"}",
            TaxBracketRequest.class);
    assertThat(ok.getBracketMin()).isEqualByComparingTo("0.00");
    assertThat(ok.getBracketMax()).isEqualByComparingTo("11000.00");
    assertThat(ok.getTaxRate()).isEqualByComparingTo("0.1");
    assertThat(ok.getBaseTax()).isEqualByComparingTo("0.00");
    assertThat(ok.malformedProperties()).isEmpty();

    for (String rate : new String[] {"\"0.2200\"", "\"1.0\"", "\"1.0000\"", "\"0.0\""}) {
      TaxBracketRequest body =
          lenient.readValue("{\"taxRate\":" + rate + "}", TaxBracketRequest.class);
      assertThat(body.getTaxRate()).as(rate).isNotNull();
      assertThat(body.malformedProperties()).as(rate).isEmpty();
    }

    for (String rate :
        new String[] {
          "0.22", "22", "\"22\"", "\"22.00\"", "\"1.1\"", "\".5\"", "\"0.12345\"", "[]"
        }) {
      TaxBracketRequest body =
          lenient.readValue(
              "{\"taxYear\":2026,\"taxRate\":" + rate + ",\"bracketMin\":10}",
              TaxBracketRequest.class);
      assertThat(body.getTaxRate()).as(rate).isNull();
      assertThat(body.getBracketMin()).as(rate).isNull();
      assertThat(body.getTaxYear()).as(rate).isEqualTo(2026);
      assertThat(body.malformedProperties()).as(rate).containsExactly("taxRate", "bracketMin");
      assertThatThrownBy(body::requireNoMalformedProperties)
          .isInstanceOfSatisfying(
              HrmsException.class,
              e -> {
                assertThat(e.field()).isEqualTo("taxRate");
                assertThat(e.details())
                    .extracting(d -> d.field() + ":" + d.code() + ":" + d.message())
                    .containsExactly(
                        "taxRate:InvalidFormat:" + TaxRateDeserializer.MESSAGE,
                        "bracketMin:InvalidFormat:" + MoneyDeserializer.MESSAGE);
              });
    }
  }

  @Test
  void holidayWindowIsTenCalendarYears() {
    HolidayRequest body = new HolidayRequest();
    LocalDate today = LocalDate.now();
    body.setHolidayDate(today.plusYears(10));
    assertThat(body.isHolidayDateWithinWindow()).isTrue();
    body.setHolidayDate(today.plusYears(10).plusDays(1));
    assertThat(body.isHolidayDateWithinWindow()).isFalse();
    body.setHolidayDate(HolidayRequest.MIN_DATE);
    assertThat(body.isHolidayDateWithinWindow()).isTrue();
    body.setHolidayDate(HolidayRequest.MIN_DATE.minusDays(1));
    assertThat(body.isHolidayDateWithinWindow()).isFalse();
  }

  @Test
  void unknownPropertiesAreCapturedOnAuthBodies() throws Exception {
    UserStatusRequest body =
        lenient.readValue(
            "{\"status\":\"DISABLED\",\"reason\":\" left \",\"bogus\":null}",
            UserStatusRequest.class);
    assertThat(body.getStatus()).isEqualTo("DISABLED");
    assertThat(body.unknownProperties()).containsExactly("bogus");
    assertThatThrownBy(body::requireNoUnknownProperties)
        .isInstanceOfSatisfying(HrmsException.class, e -> assertThat(e.field()).isEqualTo("bogus"));
  }
}
