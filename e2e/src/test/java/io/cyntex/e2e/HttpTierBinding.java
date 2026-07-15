package io.cyntex.e2e;

import io.cyntex.core.dsl.DslException;
import io.cyntex.core.dsl.DslParser;
import io.cyntex.core.dsl.Interpolator;
import io.cyntex.core.lifecycle.LifecycleVerb;
import io.cyntex.core.lifecycle.PipelineState;
import io.cyntex.core.model.Resource;
import io.cyntex.core.model.SourceResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Binds a specification to a running product over its HTTP surface.
 *
 * <p>There is one of these, not one per tier: everything that differs between tiers is how the
 * server came to be listening, which is {@link ServerHandle}'s business and settled before this
 * class sees a URL. A second binding per tier would be two chances to drift, and drift is precisely
 * what "the same specification runs on both tiers" is a promise against.
 *
 * <p>Endpoints are learned by applying them. That mirrors the product's own rule - a source must be
 * applied before a pipeline can reference it by id - so the harness cannot address an endpoint the
 * product does not have either.
 */
final class HttpTierBinding implements TierBinding {

    private static final String URI_SETTING = "uri";

    private final ControlPlane control;
    private final Path workspace;
    private final DslParser parser = new DslParser();
    private final MongoEndpoints endpoints;
    private final Map<String, SourceResource> sourcesById = new LinkedHashMap<>();

    /**
     * What a specification's {@code ${...}} references resolve to. The harness is the client here, and
     * the client is the side that interpolates, so the values are the harness's own to supply — which is
     * what lets a checked-in resource name an endpoint whose address is only known once a container is
     * up. It also works identically on both tiers: an in-process server shares this JVM, whose real
     * environment no test could set anyway.
     */
    private final UnaryOperator<String> env;

    HttpTierBinding(ControlPlane control, Path workspace, MongoEndpoints endpoints, UnaryOperator<String> env) {
        this.control = control;
        this.workspace = workspace;
        this.endpoints = endpoints;
        this.env = env;
    }

    @Override
    public void registerConnector(String connectorId) {
        control.registerConnector(connectorId, ConnectorJars.bytesFor(connectorId));
    }

    /**
     * Sends every listed resource in one apply, because the product resolves references within the
     * submitted set: a pipeline and the source it names must arrive together or the reference points
     * at nothing.
     */
    @Override
    public void applyResources(List<String> resourceFiles) {
        Map<String, String> yamlByFile = new LinkedHashMap<>();
        for (String resourceFile : resourceFiles) {
            String yaml = read(resourceFile);
            rememberEndpoint(resourceFile, yaml);
            yamlByFile.put(resourceFile, yaml);
        }
        control.apply(yamlByFile);
    }

    /** The connector and settings come from the applied source, which is the only place they are stated. */
    @Override
    public void discoverSchema(String resourceId) {
        SourceResource source = requireSource(resourceId);
        control.discoverSchema(resourceId, source.connector(), source.config());
    }

    @Override
    public void seed(TableAlias table, long rows) {
        endpoints.seed(uriOf(table), table.table(), rows);
    }

    @Override
    public void drive(String pipelineId, LifecycleVerb verb) {
        control.lifecycle(pipelineId, verb);
    }

    @Override
    public void cdc(TableAlias table, CdcOp op, long rows) {
        endpoints.cdc(uriOf(table), table.table(), op, rows);
    }

    @Override
    public long count(TableAlias table) {
        return endpoints.count(uriOf(table), table.table());
    }

    @Override
    public Optional<PipelineState> state(String pipelineId) {
        return control.state(pipelineId);
    }

    /**
     * A source carries its own connection settings, so applying one teaches the harness where that
     * endpoint lives. Resources that are not endpoints teach it nothing, which is correct.
     */
    private void rememberEndpoint(String resourceFile, String yaml) {
        Resource resource = parse(resourceFile, yaml);
        if (resource instanceof SourceResource source) {
            sourcesById.put(source.id(), source);
        }
    }

    private SourceResource requireSource(String resourceId) {
        SourceResource source = sourcesById.get(resourceId);
        if (source == null) {
            throw new EnvelopeException(
                    "no source applied for " + resourceId + "; list its resource under setup.apply");
        }
        return source;
    }

    private String uriOf(TableAlias table) {
        SourceResource source = requireSource(table.resourceId());
        if (!(source.config().get(URI_SETTING) instanceof String uri)) {
            throw new EnvelopeException(
                    table.resourceId() + " carries no " + URI_SETTING + " setting, so " + table
                            + " cannot be read from outside the product");
        }
        return uri;
    }

    /**
     * Reads a resource and resolves its references, exactly as the CLI does before an apply — so what the
     * product is handed here is what an author's own apply would hand it.
     *
     * <p>The resolved text is then used for both halves of the harness's job: it goes to the product, and
     * it is what {@link #rememberEndpoint} reads the endpoint address out of. One substitution feeds both,
     * so the address the harness dials cannot drift from the one the product was given.
     */
    private String read(String resourceFile) {
        String yaml;
        try {
            yaml = Files.readString(workspace.resolve(resourceFile));
        } catch (IOException e) {
            throw new EnvelopeException("cannot read the resource referenced as " + resourceFile, e);
        }
        try {
            return Interpolator.interpolate(yaml, env);
        } catch (DslException e) {
            throw new EnvelopeException(resourceFile + " has a reference that does not resolve: "
                    + e.getMessage(), e);
        }
    }

    /** Only a DSL error is the author's; anything else the parser throws is the parser's own defect. */
    private Resource parse(String resourceFile, String yaml) {
        try {
            return parser.parse(yaml);
        } catch (DslException e) {
            throw new EnvelopeException(resourceFile + " does not parse: " + e.getMessage(), e);
        }
    }
}
