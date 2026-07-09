package io.cyntex.control.restapi;

import io.cyntex.messages.MessageCatalog;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The HTTP presentation configuration: the single Spring context's Web MVC face over the control
 * core. It is imported into the one service context (there is no second application context and no
 * second port); the control verbs are projected onto HTTP here.
 *
 * <p>Every {@code @RestController} handler is mounted under the single {@code /api} prefix, so the
 * verb surface lives at one route root. A plain {@code @Controller} (not annotated
 * {@code @RestController}) is left unprefixed, so the root-level probe and the pre-authentication
 * entry points (login, bootstrap) are served outside {@code /api}.
 *
 * <p>The {@code /api} surface is guarded by the {@link AuthInterceptor}, registered here on
 * {@code /api/**} whenever an interceptor bean is present. Supplying that bean is what engages the guard,
 * so the assembly root must supply it for the running server's verb surface to be authenticated. The
 * registration is opt-in (via {@link ObjectProvider}) so a focused test asserting a non-auth concern —
 * the path-prefix mechanics, or the registry-to-endpoint projection — may omit it; such a test stands up
 * no externally reachable surface.
 */
@Configuration
public class RestApiConfiguration {

    @Bean
    WebMvcConfigurer apiWebMvc(ObjectProvider<AuthInterceptor> authInterceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void configurePathMatch(PathMatchConfigurer configurer) {
                configurer.addPathPrefix("/api", HandlerTypePredicate.forAnnotation(RestController.class));
            }

            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                AuthInterceptor interceptor = authInterceptor.getIfAvailable();
                if (interceptor != null) {
                    registry.addInterceptor(interceptor).addPathPatterns("/api/**");
                }
            }
        };
    }

    /**
     * The shared error-code catalog + renderer, so this HTTP face renders coded errors the same way every
     * other presentation face does (the offline CLI, the assembly root). The bundled {@code en} catalog is
     * immutable and stateless, so a single instance is shared.
     */
    @Bean
    MessageCatalog messageCatalog() {
        return MessageCatalog.bundled();
    }
}
