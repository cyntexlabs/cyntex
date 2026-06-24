package io.cyntex.core.model;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The {@code type:} discriminator value of a record that is one variant of a flattened
 * discriminated union (a transform body: js / map / filter / union / nest / join). The schema
 * generator emits this as a required {@code type} constant and treats the record as a partial
 * (its fields merge into the enclosing object), so the schema matches the flattened YAML form.
 *
 * <p>Metadata only: never affects parsing or serialization.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface YamlType {

    String value();
}
