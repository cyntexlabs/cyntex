package io.cyntex.core.model;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a list-valued record component whose YAML form is a bare scalar when it holds a single
 * element and a list when it holds several (the pipeline {@code source} sugar). The schema
 * generator emits it as {@code oneOf[ <item>, array-of-<item> ]} so both canonical forms validate.
 *
 * <p>Metadata only: never affects parsing or serialization.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface YamlScalarOrList {
}
