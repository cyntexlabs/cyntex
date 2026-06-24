package io.cyntex.core.model;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the YAML surface form of a model type whose canonical serialization is not an object
 * — a single value wrapped in a record (a bare name, a {@code /…/} regex, an {@code =CEL}
 * expression, a {@code from:} list or alias map, a {@code format:} field map) or a field-drop
 * {@code false}. The schema generator inlines this form instead of the record's object shape, so
 * the generated schema matches what canonical {@code .cyn.yml} actually writes.
 *
 * <p>Object-form types carry no annotation (the default). Metadata only: never affects parsing
 * or serialization.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface YamlForm {

    Form value();

    enum Form {
        /**
         * The record wraps a single value (its sole component); the YAML form is that value —
         * a string, a list, a map, or any literal — not an object with the component's name.
         */
        UNWRAP,
        /** The literal {@code false} — the field-drop marker (a component-less record). */
        FALSE
    }
}
