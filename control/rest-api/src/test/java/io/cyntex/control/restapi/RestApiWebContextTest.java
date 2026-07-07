package io.cyntex.control.restapi;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A single embedded web context boots on one port, and every {@code @RestController} handler is
 * served under the {@code /api} prefix and nowhere else. This witnesses the single-context,
 * single-port HTTP substrate and the one route prefix the control verbs project onto.
 *
 * <p>The context is booted programmatically (not through the Spring JUnit extension) so the module
 * needs no test-harness dependency beyond the reactor's JUnit line.
 */
class RestApiWebContextTest {

    private static ConfigurableApplicationContext context;
    private static int port;

    @BeforeAll
    static void startServer() {
        context = new SpringApplicationBuilder(TestApp.class)
                .properties("server.port=0")
                .run();
        port = ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (context != null) {
            context.close();
        }
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    @Test
    void restControllerHandlersAreServedUnderTheApiPrefix() {
        ResponseEntity<String> underApi = client().get().uri("/api/probe").retrieve().toEntity(String.class);
        assertThat(underApi.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(underApi.getBody()).isEqualTo("ok");
    }

    @Test
    void restControllerHandlersAreNotServedAtTheRoot() {
        // exchange() reads the raw response without the default 4xx/5xx throwing, so a 404 is an assertable value.
        HttpStatusCode atRoot = client().get().uri("/probe")
                .exchange((request, response) -> response.getStatusCode());
        assertThat(atRoot).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * A minimal boot config: auto-configures Web MVC + the embedded servlet container, imports the
     * configuration under test and a probe endpoint. No component scan, so nothing else is pulled in.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({RestApiConfiguration.class, ProbeController.class})
    static class TestApp {
    }

    @RestController
    static class ProbeController {

        @GetMapping("/probe")
        String probe() {
            return "ok";
        }
    }
}
