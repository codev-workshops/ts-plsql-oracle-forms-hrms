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
}
