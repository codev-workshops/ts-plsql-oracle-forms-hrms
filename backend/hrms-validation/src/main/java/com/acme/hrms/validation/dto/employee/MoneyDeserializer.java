package com.acme.hrms.validation.dto.employee;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonStreamContext;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.regex.Pattern;
import org.springframework.lang.Nullable;

/**
 * {@code Money} of contracts/p3-employee/openapi.yaml: a JSON string matching {@link #WIRE_PATTERN}
 * (never a JSON number). Any other token is recorded on the owning {@link StrictRequest} as a
 * malformed property and left {@code null}, so binding never fails and the frozen precedence of
 * authority, module flag and headers over body checks is kept.
 */
public final class MoneyDeserializer extends StdDeserializer<BigDecimal> {

  public static final String WIRE_PATTERN = "^-?[0-9]+\\.[0-9]{2}$";
  public static final String MESSAGE = "Must be a decimal string with exactly two decimals";

  private static final Pattern WIRE = Pattern.compile(WIRE_PATTERN);

  public MoneyDeserializer() {
    super(BigDecimal.class);
  }

  @Override
  @Nullable
  public BigDecimal deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
    if (p.hasToken(JsonToken.VALUE_STRING)) {
      String text = p.getText();
      if (WIRE.matcher(text).matches()) {
        return new BigDecimal(text);
      }
    }
    String name = p.currentName();
    JsonStreamContext context = p.getParsingContext();
    if (p.currentToken().isStructStart()) {
      context = context.getParent();
      p.skipChildren();
    }
    Object owner = context == null ? null : context.getCurrentValue();
    if (owner instanceof StrictRequest strict) {
      strict.malformedProperty(name);
      return null;
    }
    return (BigDecimal) ctxt.handleWeirdStringValue(BigDecimal.class, p.getText(), MESSAGE);
  }
}
