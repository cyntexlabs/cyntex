package io.cyntex.adapters.pdk;

import io.cyntex.core.common.CyntexException;
import io.cyntex.core.common.JsonReader;
import io.cyntex.spi.store.ConnectorRegistry;
import io.cyntex.spi.store.RegistrationOutcome;
import io.cyntex.spi.store.RegistrationSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Registers a connector artifact on disk into the distribution store by what the artifact itself
 * declares: self-scan supplies the entry class and PDK API version, the spec's {@code properties.id}
 * supplies the connector id, and the artifact bytes go through the store's content-hash idempotent
 * register-if-absent. The startup seed sweep and the explicit runtime register operation are both
 * this one call, differing only in the {@link RegistrationSource} they record.
 *
 * <p>An artifact whose spec cannot yield an id — not valid JSON, or no {@code properties.id} — is
 * refused with a coded connector-domain exception: registration would have no identity to file the
 * artifact under. A raw I/O failure reading the artifact is not a connector defect and surfaces as
 * an unchecked I/O exception.
 */
public final class ConnectorArtifactRegistrar {

    private final ConnectorRegistry registry;
    private final ConnectorIntrospector introspector;

    public ConnectorArtifactRegistrar(ConnectorRegistry registry, ConnectorIntrospector introspector) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.introspector = Objects.requireNonNull(introspector, "introspector");
    }

    /** Registers the artifact at {@code artifact} if its content hash is not already registered. */
    public RegistrationOutcome register(Path artifact, RegistrationSource source) {
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(source, "source");
        IntrospectedConnector introspected = introspector.introspect(List.of(artifact));
        String connectorId = specDeclaredId(introspected, artifact);
        return registry.register(connectorId, introspected.pdkApiVersion(), source, bytesOf(artifact));
    }

    /** The connector id the spec declares under {@code properties.id} — the registration identity. */
    private static String specDeclaredId(IntrospectedConnector introspected, Path artifact) {
        Object tree;
        try {
            tree = JsonReader.parse(introspected.spec());
        } catch (IllegalArgumentException e) {
            throw specInvalid(introspected, artifact, "the spec is not valid JSON", e);
        }
        if (tree instanceof Map<?, ?> root
                && root.get("properties") instanceof Map<?, ?> properties
                && properties.get("id") instanceof String id
                && !id.isBlank()) {
            return id;
        }
        throw specInvalid(introspected, artifact, "the spec does not declare properties.id as a non-blank string", null);
    }

    private static CyntexException specInvalid(
            IntrospectedConnector introspected, Path artifact, String detail, Throwable cause) {
        return new CyntexException(ConnectorError.SPEC_INVALID,
                Map.of("artifact", artifact.getFileName().toString(),
                        "spec", introspected.specPath(),
                        "detail", detail),
                cause);
    }

    private static byte[] bytesOf(Path artifact) {
        try {
            return Files.readAllBytes(artifact);
        } catch (IOException e) {
            throw new UncheckedIOException("reading connector artifact " + artifact, e);
        }
    }
}
