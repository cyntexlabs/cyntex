package io.cyntex.core.model;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a record component whose discriminated-union value is flattened into the enclosing
 * object rather than nested under the component's key — the transform {@code body} of a step or
 * {@code kind: transform} resource, whose {@code type:} and type-specific fields sit directly
 * beside {@code id} / {@code from}. The schema generator composes the union's variants into the
 * enclosing object instead of emitting this component as a property.
 *
 * <p>Metadata only: never affects parsing or serialization.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface YamlFlatten {
}
