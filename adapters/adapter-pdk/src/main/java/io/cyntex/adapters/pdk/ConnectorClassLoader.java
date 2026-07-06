package io.cyntex.adapters.pdk;

import io.tapdata.pdk.apis.TapConnector;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

/**
 * A live, isolated class loader for one connector.
 *
 * <p>Each connector runs on its own class loader over its own jar (plus any bundled dependencies),
 * so two connectors never see each other's classes and a connector can be dropped by closing its
 * loader. The one thing shared across the boundary is the frozen PDK contract ({@code io.tapdata.*}),
 * which is delegated to the host: every connector binds to the same {@code TapConnector} /
 * {@code TapEvent} types, so events cross the boundary as one contract. Everything else on the host —
 * the cyntex application classes and the service framework libraries — is hidden from the connector.
 */
public final class ConnectorClassLoader implements AutoCloseable {

    /** The frozen PDK contract is the only host layer a connector is allowed to see. */
    private static final String SHARED_API_PREFIX = "io.tapdata.";

    private final URLClassLoader loader;

    private ConnectorClassLoader(URLClassLoader loader) {
        this.loader = loader;
    }

    /** Opens an isolated loader over {@code classpath} (the connector jar plus any bundled deps). */
    public static ConnectorClassLoader open(List<Path> classpath) {
        URL[] urls = classpath.stream().map(ConnectorClassLoader::toUrl).toArray(URL[]::new);
        ClassLoader host = ConnectorClassLoader.class.getClassLoader();
        ClassLoader sharedContract = new SharedContractClassLoader(host);
        return new ConnectorClassLoader(new URLClassLoader(urls, sharedContract));
    }

    /** Loads {@code className} in isolation (from the connector jar or the shared PDK contract). */
    public Class<?> load(String className) throws ClassNotFoundException {
        return loader.loadClass(className);
    }

    /**
     * Loads {@code className} and confirms it is a connector entry class. Only checks the type; it
     * does not instantiate or initialize the connector.
     */
    public Class<? extends TapConnector> loadConnectorClass(String className)
            throws ClassNotFoundException {
        Class<?> loaded = load(className);
        if (!TapConnector.class.isAssignableFrom(loaded)) {
            throw new IllegalStateException(
                    className + " is not a " + TapConnector.class.getName());
        }
        return loaded.asSubclass(TapConnector.class);
    }

    @Override
    public void close() {
        try {
            loader.close();
        } catch (IOException e) {
            throw new UncheckedIOException("closing connector class loader", e);
        }
    }

    private static URL toUrl(Path jar) {
        try {
            return jar.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("bad connector jar path " + jar, e);
        }
    }

    /**
     * The parent a connector loader delegates to: it exposes only the shared PDK contract from the
     * host and hides everything else. Its own parent is the platform loader, so {@code java.*} /
     * {@code javax.*} still resolve; any non-contract host class (cyntex, Spring, Hazelcast, Mongo)
     * is not found, so the connector cannot bind to it.
     */
    private static final class SharedContractClassLoader extends ClassLoader {

        private final ClassLoader host;

        SharedContractClassLoader(ClassLoader host) {
            super(ClassLoader.getPlatformClassLoader());
            this.host = host;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (name.startsWith(SHARED_API_PREFIX)) {
                return host.loadClass(name);
            }
            throw new ClassNotFoundException(name);
        }
    }
}
