package io.cyntex.app;

import io.cyntex.adapters.mongostore.MongoAuthStores;
import io.cyntex.adapters.mongostore.MongoConnection;
import io.cyntex.control.core.ApplyService;
import io.cyntex.control.core.ArtifactQueryService;
import io.cyntex.control.core.AuditGate;
import io.cyntex.control.core.BootstrapService;
import io.cyntex.control.core.ControlOperations;
import io.cyntex.control.core.CredentialAuthenticator;
import io.cyntex.control.core.LoginService;
import io.cyntex.control.core.OperationRegistry;
import io.cyntex.control.core.PasswordHasher;
import io.cyntex.control.core.PipelineLifecycleService;
import io.cyntex.control.core.PipelineLogQueryService;
import io.cyntex.control.core.PipelineObservationQueryService;
import io.cyntex.control.core.TokenSecrets;
import io.cyntex.control.core.TokenService;
import io.cyntex.control.core.TokenSigner;
import io.cyntex.control.restapi.ControlHttpFace;
import io.cyntex.core.catalog.CyntexCatalog;
import io.cyntex.core.logging.LogSink;
import io.cyntex.core.logging.RingBufferLogSink;
import io.cyntex.spi.store.AuditStore;
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
@EnableConfigurationProperties(ControlAuthProperties.class)
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
    ApplyService applyService(StorePort storePort) {
        return new ApplyService(CyntexCatalog.load(), storePort.artifacts());
    }

    @Bean
    ArtifactQueryService artifactQueryService(StorePort storePort) {
        return new ArtifactQueryService(storePort.artifacts());
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
