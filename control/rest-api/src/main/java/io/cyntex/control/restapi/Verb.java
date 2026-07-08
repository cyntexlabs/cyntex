package io.cyntex.control.restapi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an HTTP handler method as the projection of one control operation, naming its operation id. The
 * HTTP face composes registered operations; it never invents an endpoint. An architecture test reads
 * this annotation off every {@code /api} handler and asserts each value is a registered, CLI-exposed
 * operation — so the endpoint table stays a derivation of the registry rather than a hand-kept list.
 *
 * <p>Runtime-retained so the test can read it off the live handler mapping; it carries no behavior at
 * request time — dispatch does not consult it — so it adds no runtime reflection to serving a request.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Verb {

    /** The dot-scoped operation id this endpoint projects, e.g. {@code artifact.apply}. */
    String value();
}
