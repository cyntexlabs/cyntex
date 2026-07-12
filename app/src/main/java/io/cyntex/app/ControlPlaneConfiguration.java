package io.cyntex.app;

import io.cyntex.adapters.mongostore.MongoAuthStores;
import io.cyntex.adapters.mongostore.MongoConnection;
import io.cyntex.adapters.pdk.ConnectorIntrospector;
import io.cyntex.adapters.pdk.ConnectorProvisioner;
import io.cyntex.adapters.pdk.PdkConnectionTester;
import io.cyntex.adapters.pdk.RegistryConnectorProvisioner;
import io.cyntex.control.core.ApplyService;
import io.cyntex.control.core.ArtifactQueryService;
import io.cyntex.control.core.AuditGate;
import io.cyntex.control.core.BootstrapService;
import io.cyntex.control.core.ConnectionTestResultQueryService;
import io.cyntex.control.core.ConnectionTestService;
import io.cyntex.control.core.ControlOperations;
import io.cyntex.control.core.CredentialAuthenticator;
import io.cyntex.control.core.LoginService;
import io.cyntex.control.core.OperationRegistry;
import io.cyntex.control.core.PasswordHasher;
import io.cyntex.control.core.TokenSecrets;
import io.cyntex.control.core.TokenService;
import io.cyntex.control.core.TokenSigner;
import io.cyntex.control.restapi.ControlHttpFace;
import io.cyntex.core.catalog.CyntexCatalog;
import io.cyntex.runtime.probe.ConnectionProbe;
import io.cyntex.runtime.probe.DelegatingConnectionProbe;
import io.cyntex.spi.store.AuditStore;
import io.cyntex.spi.store.ConnectionTestResultStore;
import io.cyntex.spi.store.ConnectionTester;
import io.cyntex.spi.store.ConnectorRegistry;
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
    ApplyService applyService(StorePort storePort) {
        return new ApplyService(CyntexCatalog.load(), storePort.artifacts());
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
