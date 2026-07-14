package io.cyntex.app;

import io.cyntex.adapters.mongostore.MongoAuthStores;
import io.cyntex.adapters.mongostore.MongoConnection;
import io.cyntex.adapters.pdk.ConnectorArtifactRegistrar;
import io.cyntex.adapters.pdk.ConnectorIntrospector;
import io.cyntex.adapters.pdk.ConnectorProvisioner;
import io.cyntex.adapters.pdk.PdkCapabilityDeriver;
import io.cyntex.adapters.pdk.PdkConnectionTester;
import io.cyntex.adapters.pdk.PdkSchemaDiscoverer;
import io.cyntex.adapters.pdk.RegistryConnectorProvisioner;
import io.cyntex.adapters.pdk.SeedConnectorSweep;
import io.cyntex.control.core.ApplyService;
import io.cyntex.control.core.ConnectorCatalogView;
import io.cyntex.control.core.ArtifactQueryService;
import io.cyntex.control.core.AuditGate;
import io.cyntex.control.core.BootstrapService;
import io.cyntex.control.core.ConnectionTestResultQueryService;
import io.cyntex.control.core.ConnectionTestService;
import io.cyntex.control.core.ConnectorRegisterService;
import io.cyntex.control.core.ControlOperations;
import io.cyntex.control.core.CredentialAuthenticator;
import io.cyntex.control.core.LoginService;
import io.cyntex.control.core.OperationRegistry;
import io.cyntex.control.core.PasswordHasher;
import io.cyntex.control.core.PipelineLifecycleService;
import io.cyntex.control.core.PipelineLogQueryService;
import io.cyntex.control.core.PipelineObservationQueryService;
import io.cyntex.control.core.SchemaDiscoveryService;
import io.cyntex.control.core.SchemaQueryService;
import io.cyntex.control.core.TokenSecrets;
import io.cyntex.control.core.TokenService;
import io.cyntex.control.core.TokenSigner;
import io.cyntex.control.restapi.ControlHttpFace;
import io.cyntex.core.catalog.CyntexCatalog;
import io.cyntex.core.logging.LogSink;
import io.cyntex.core.logging.RingBufferLogSink;
import io.cyntex.runtime.probe.ConnectionProbe;
import io.cyntex.runtime.probe.DelegatingConnectionProbe;
import io.cyntex.runtime.probe.DelegatingSchemaDiscoveryProbe;
import io.cyntex.runtime.probe.SchemaDiscoveryProbe;
import io.cyntex.spi.store.AuditStore;
import io.cyntex.spi.store.ConnectionTestResultStore;
import io.cyntex.spi.store.ConnectionTester;
import io.cyntex.spi.store.CapabilityDeriver;
import io.cyntex.spi.store.ConnectorCatalogStore;
import io.cyntex.spi.store.ConnectorRegistry;
import io.cyntex.spi.store.SchemaDiscoverer;
import io.cyntex.spi.store.SchemaStore;
import io.cyntex.spi.store.StorePort;
import io.cyntex.spi.store.TokenStore;
import io.cyntex.spi.store.UserStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;

/**
 * Wires the control plane into the assembly root: the authentication ports over the store, the control-core
 * services, and the HTTP control face ({@link ControlHttpFace}) that projects the verbs onto an
 * authenticated {@code /api} surface. This is the driver-free service graph the running server exposes
 * under {@code --role=all}.
 *
 * <p>Gated, like the store it stands on, on {@code cyntex.store.mongo.enabled}: the control plane persists
 * to the store, so a run with no store (a substrate check) brings up neither the store nor the control
 * plane. Because the whole face — controllers and the interceptor that guards them — is imported together
 * through {@link ControlHttpFace}, there is no state in which the verb surface is served without the guard:
 * either the store is present and the entire authenticated face comes up, or it is absent and none of it
 * does.
 */
@Configuration
@ConditionalOnProperty(prefix = "cyntex.store.mongo", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties({ControlAuthProperties.class, ConnectorPluginProperties.class})
@Import(ControlHttpFace.class)
class ControlPlaneConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(ControlPlaneConfiguration.class);

    /** The number of random bytes in an ephemeral signing secret when none is configured. */
    private static final int EPHEMERAL_SECRET_BYTES = 32;

    /** The node-local log tail bounds: how many pipelines to retain lines for, and how many lines each. */
    private static final int MAX_LOGGED_PIPELINES = 64;
    private static final int MAX_LOG_LINES_PER_PIPELINE = 200;

    // ---- authentication ports over the store (the counterpart to StoreConfiguration's StorePort) ----

    @Bean
    MongoAuthStores authStores(MongoConnection storeConnection) {
        return new MongoAuthStores(storeConnection);
    }

    @Bean
    UserStore userStore(MongoAuthStores authStores) {
        return authStores.users();
    }

    @Bean
    TokenStore tokenStore(MongoAuthStores authStores) {
        return authStores.tokens();
    }

    @Bean
    AuditStore auditStore(MongoAuthStores authStores) {
        return authStores.audit();
    }

    // ---- the framework-free primitives bound to their control-ring ports ----

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    PasswordHasher passwordHasher() {
        return new BCryptPasswordHasher();
    }

    @Bean
    TokenSecrets tokenSecrets() {
        return new RandomTokenSecrets();
    }

    @Bean
    TokenSigner tokenSigner(ControlAuthProperties properties, Clock clock) {
        return new HmacTokenSigner(resolveSigningSecret(properties), HmacTokenSigner.DEFAULT_TTL, clock);
    }

    // ---- the control-core services (stateless, composed over the ports above) ----

    @Bean
    OperationRegistry operationRegistry() {
        return ControlOperations.registry();
    }

    @Bean
    TokenService tokenService(TokenStore tokenStore, TokenSecrets tokenSecrets, Clock clock) {
        return new TokenService(tokenStore, tokenSecrets, clock);
    }

    @Bean
    LoginService loginService(UserStore userStore, PasswordHasher passwordHasher, TokenSigner tokenSigner) {
        return new LoginService(userStore, passwordHasher, tokenSigner);
    }

    @Bean
    AuditGate auditGate(AuditStore auditStore, Clock clock) {
        return new AuditGate(auditStore, clock);
    }

    @Bean
    BootstrapService bootstrapService(UserStore userStore, PasswordHasher passwordHasher, AuditGate auditGate) {
        return new BootstrapService(userStore, passwordHasher, auditGate);
    }

    @Bean
    CredentialAuthenticator credentialAuthenticator(TokenService tokenService, TokenSigner tokenSigner) {
        return new CredentialAuthenticator(tokenService, tokenSigner);
    }

    @Bean
    ApplyService applyService(StorePort storePort, ConnectorCatalogView connectorCatalogView) {
        // The online apply validates against the live catalog view (the bundled snapshot union the
        // connectors registered so far), so a connector registered at runtime is honoured without a restart.
        return new ApplyService(connectorCatalogView::merged, storePort.artifacts());
    }

    @Bean
    ArtifactQueryService artifactQueryService(StorePort storePort) {
        return new ArtifactQueryService(storePort.artifacts());
    }

    // ---- the connector plane: the R5 synchronous connection-test verb, wired end to end ----
    // control-core service -> runtime probe -> adapter-pdk tester -> provisioner -> connector registry.
    // The PDK types stay inside the adapter-pdk beans; the runtime and control rings see only ports.

    @Bean
    ConnectorRegistry connectorRegistry(StorePort storePort) {
        return storePort.connectors();
    }

    @Bean
    ConnectorCatalogStore connectorCatalogStore(StorePort storePort) {
        return storePort.connectorCatalog();
    }

    @Bean
    CapabilityDeriver capabilityDeriver(ConnectorProvisioner provisioner) {
        return new PdkCapabilityDeriver(provisioner);
    }

    @Bean
    ConnectorCatalogView connectorCatalogView(ConnectorCatalogStore connectorCatalogStore) {
        // The online catalog view = the bundled snapshot overlaid with the rows derived for registered
        // connectors, read live; the offline native CLI keeps reading only the bundled snapshot.
        return new ConnectorCatalogView(CyntexCatalog.load(), connectorCatalogStore);
    }

    @Bean
    ConnectionTestResultStore connectionTestResultStore(StorePort storePort) {
        return storePort.connectionTestResults();
    }

    @Bean
    ConnectorIntrospector connectorIntrospector() {
        return new ConnectorIntrospector();
    }

    @Bean
    ConnectorProvisioner connectorProvisioner(
            ConnectorRegistry registry, ConnectorIntrospector introspector, ConnectorPluginProperties properties) {
        return new RegistryConnectorProvisioner(registry, introspector, properties.getPluginsDir());
    }

    // The startup seed sweep: the release's connectors/ directory goes through the same
    // register-if-absent path an explicit register uses, so restarts and concurrent nodes are harmless.

    @Bean
    ConnectorArtifactRegistrar connectorArtifactRegistrar(
            ConnectorRegistry registry, ConnectorIntrospector introspector,
            CapabilityDeriver capabilityDeriver, ConnectorCatalogStore connectorCatalogStore) {
        return new ConnectorArtifactRegistrar(registry, introspector, capabilityDeriver, connectorCatalogStore);
    }

    @Bean
    SeedConnectorSweep seedConnectorSweep(ConnectorArtifactRegistrar registrar) {
        return new SeedConnectorSweep(registrar);
    }

    @Bean
    SeedSweepRunner seedSweepRunner(SeedConnectorSweep sweep, ConnectorPluginProperties properties) {
        return new SeedSweepRunner(sweep, properties.getSeedDir());
    }

    @Bean
    ConnectorRegisterService connectorRegisterService(ConnectorArtifactRegistrar registrar, AuditGate auditGate) {
        // The register verb reaches the distribution store through the same registrar the seed sweep uses; it
        // implements the spi ingestion port, so control-core drives it without depending on the adapters ring.
        return new ConnectorRegisterService(registrar, auditGate);
    }

    @Bean
    ConnectionTester connectionTester(ConnectorProvisioner provisioner, Clock clock) {
        return new PdkConnectionTester(provisioner, clock);
    }

    @Bean
    ConnectionProbe connectionProbe(ConnectionTester tester) {
        return new DelegatingConnectionProbe(tester);
    }

    @Bean
    ConnectionTestService connectionTestService(
            ConnectionProbe probe, ConnectionTestResultStore resultStore, AuditGate auditGate) {
        return new ConnectionTestService(probe, resultStore, auditGate);
    }

    @Bean
    ConnectionTestResultQueryService connectionTestResultQueryService(ConnectionTestResultStore resultStore) {
        return new ConnectionTestResultQueryService(resultStore);
    }

    // The schema-discovery half of the connection plane: the same provisioner feeds the PDK discoverer,
    // injected into the runtime seam; discovered models persist through the schema store.

    @Bean
    SchemaStore schemaStore(StorePort storePort) {
        return storePort.schemas();
    }

    @Bean
    SchemaDiscoverer schemaDiscoverer(ConnectorProvisioner provisioner) {
        return new PdkSchemaDiscoverer(provisioner);
    }

    @Bean
    SchemaDiscoveryProbe schemaDiscoveryProbe(SchemaDiscoverer discoverer) {
        return new DelegatingSchemaDiscoveryProbe(discoverer);
    }

    @Bean
    SchemaDiscoveryService schemaDiscoveryService(
            SchemaDiscoveryProbe probe, SchemaStore schemaStore, AuditGate auditGate, Clock clock) {
        return new SchemaDiscoveryService(probe, schemaStore, auditGate, clock);
    }

    @Bean
    SchemaQueryService schemaQueryService(SchemaStore schemaStore) {
        return new SchemaQueryService(schemaStore);
    }

    @Bean
    PipelineLifecycleService pipelineLifecycleService(
            ArtifactQueryService artifactQueryService, StorePort storePort, AuditGate auditGate) {
        return new PipelineLifecycleService(artifactQueryService, storePort.desired(), auditGate);
    }

    @Bean
    PipelineObservationQueryService pipelineObservationQueryService(StorePort storePort) {
        return new PipelineObservationQueryService(storePort.observations());
    }

    // ---- the node-local log tail: the sink, the appender that feeds it, and the read face over it ----

    /**
     * The one in-process log sink for this node. It is node-local, not store-backed: logs are not fanned
     * into the shared store like the other observation reads, so this bean is both fed (by the appender) and
     * read (by the logs read face) in-process.
     */
    @Bean
    LogSink logSink() {
        return new RingBufferLogSink(MAX_LOGGED_PIPELINES, MAX_LOG_LINES_PER_PIPELINE);
    }

    /** Attaches the pipeline log appender to the logging backend so the sink is fed; detaches on shutdown. */
    @Bean
    PipelineLogCapture pipelineLogCapture(LogSink logSink) {
        return new PipelineLogCapture(logSink);
    }

    @Bean
    PipelineLogQueryService pipelineLogQueryService(LogSink logSink) {
        return new PipelineLogQueryService(logSink);
    }

    /**
     * The signing secret from configuration, or a fresh random one when none is set. An unset secret is a
     * working single-node default, not an error — but session tokens then do not outlive a restart nor
     * cross nodes, so a warning names the trade-off.
     */
    private static byte[] resolveSigningSecret(ControlAuthProperties properties) {
        String configured = properties.getJwtSecret();
        if (configured != null && !configured.isBlank()) {
            return configured.getBytes(StandardCharsets.UTF_8);
        }
        byte[] ephemeral = new byte[EPHEMERAL_SECRET_BYTES];
        new SecureRandom().nextBytes(ephemeral);
        LOG.warn("No cyntex.control.auth.jwt-secret is configured; signing session tokens with an ephemeral "
                + "secret. Tokens will not survive a restart or work across nodes -- set a secret for a "
                + "restart-stable or multi-node deployment.");
        return ephemeral;
    }
}
