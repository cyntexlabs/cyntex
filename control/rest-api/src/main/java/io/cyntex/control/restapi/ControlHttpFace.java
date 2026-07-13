package io.cyntex.control.restapi;

import io.cyntex.control.core.CredentialAuthenticator;
import io.cyntex.control.core.OperationRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The public assembly entry point for the whole HTTP control face: the path-prefix + interceptor
 * registration, every verb controller, the pre-authentication entry points, the anonymous probe, and the
 * coded-error advice — bundled with the authentication interceptor that guards them. The assembly root
 * imports this one configuration to serve the control plane over HTTP; the individual controllers and the
 * interceptor stay package-private and are wired here, from inside their own package.
 *
 * <p>Bundling the interceptor with the controllers is what makes the surface fail closed: the only way to
 * bring the verb controllers into a context is through this configuration, and it always supplies the
 * {@link AuthInterceptor} (which {@link RestApiConfiguration} then registers on {@code /api/**}). A context
 * that serves these verbs therefore cannot come up without the guard — the interceptor's own dependencies
 * ({@link OperationRegistry}, {@link CredentialAuthenticator}) must be present or the context fails to
 * start, rather than silently serving an unguarded surface.
 */
@Configuration
@Import({RestApiConfiguration.class, ArtifactController.class, ConnectionController.class,
        PipelineController.class, PipelineObservationController.class, PipelineLogsController.class,
        ClusterController.class, HealthController.class, AuthController.class, ApiExceptionHandler.class})
public class ControlHttpFace {

    @Bean
    AuthInterceptor authInterceptor(OperationRegistry registry, CredentialAuthenticator credentials) {
        return new AuthInterceptor(registry, credentials);
    }
}
