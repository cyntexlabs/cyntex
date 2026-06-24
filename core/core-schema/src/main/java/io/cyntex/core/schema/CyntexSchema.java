package io.cyntex.core.schema;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Runtime access to the {@code cyntex/v1} JSON Schema. The schema is generated from the resource
 * model at build time and bundled as a static resource; this loads that resource — no reflection,
 * so it is free on a native image. Consumers (CLI validate / explain / completion, IDE
 * association) read the schema text from here.
 */
public final class CyntexSchema {

    private static final String RESOURCE = "/schema/cyntex-v1.schema.json";
    private static final String ID = "https://cyntex.io/schema/cyntex/v1.json";

    private CyntexSchema() {
    }

    /** The schema's {@code $id}. */
    public static String id() {
        return ID;
    }

    /** The bundled schema document as text. */
    public static String json() {
        try (InputStream in = CyntexSchema.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("bundled schema resource missing: " + RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("reading bundled schema " + RESOURCE, e);
        }
    }
}
