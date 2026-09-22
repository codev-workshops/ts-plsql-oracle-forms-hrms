package com.acme.hrms.validation.meta;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Export hints that Bean Validation cannot express (trim behaviour and the client-side messages).
 * Read by {@code ValidationSchemaExporter} together with the standard constraints.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface FieldMeta {
  boolean trim() default false;

  String requiredMessage() default "";

  String formatMessage() default "";

  String patternMessage() default "";

  String maxLengthMessage() default "";

  /**
   * When set on a numeric field, the min/max bounds are additionally exported as a {@code rules}
   * entry carrying this id, the legacy {@code ApiError.code} and message (e.g. {@code -20403}). On
   * a date field a single {@code kind=custom} rule is exported whose {@code value} is {@link
   * #ruleValue()} (e.g. the sibling field a date must not precede, or a day limit). On a string
   * field a single {@code kind=pattern} rule is exported carrying the field's {@code @Pattern} /
   * custom-constraint regex.
   */
  String ruleId() default "";

  String ruleValue() default "";

  String ruleErrorCode() default "";

  String ruleMessage() default "";

  /**
   * Exported as {@code "sensitive": true}: the client must mask the input and must never expect the
   * value back in any response (SSN, COMPONENT_MAPPING.md §8).
   */
  boolean sensitive() default false;
}
