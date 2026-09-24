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
 * A decimal wire type that is always a JSON string matching a frozen pattern (never a JSON number).
 * Any other token is recorded on the owning {@link StrictRequest} as a malformed property and left
 * {@code null}, so binding never fails and the frozen precedence of authority, module flag and
 * headers over body checks is kept.
 */
public abstract class WireDecimalDeserializer extends StdDeserializer<BigDecimal> {

  private final Pattern wire;
  private final String message;

  protected WireDecimalDeserializer(String wirePattern, String message) {
    super(BigDecimal.class);
    this.wire = Pattern.compile(wirePattern);
    this.message = message;
  }

  @Override
  @Nullable
  public BigDecimal deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
    if (p.hasToken(JsonToken.VALUE_STRING)) {
      String text = p.getText();
      if (wire.matcher(text).matches()) {
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
      strict.malformedProperty(name, message);
      return null;
    }
    return (BigDecimal) ctxt.handleWeirdStringValue(BigDecimal.class, p.getText(), message);
  }
}
