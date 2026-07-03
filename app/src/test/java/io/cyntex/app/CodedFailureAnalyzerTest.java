package io.cyntex.app;

import io.cyntex.adapters.mongostore.StoreError;
import io.cyntex.core.common.CyntexException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;

import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A coded startup failure is rendered through the shared catalog — the operator sees the same
 * message the CLI would show, not a stack trace.
 */
class CodedFailureAnalyzerTest {

    @Test
    void rendersACodedExceptionThroughTheCatalog() {
        CyntexException coded = new CyntexException(
                StoreError.UNREACHABLE, Map.of("target", "localhost:27017"), null);

        FailureAnalysis analysis = new CodedFailureAnalyzer().analyze(coded);

        assertThat(analysis).as("a coded startup failure produces an analysis").isNotNull();
        assertThat(analysis.getDescription()).isEqualTo("Cannot reach the store at localhost:27017.");
        assertThat(analysis.getAction())
                .isEqualTo("Check the store is running and the connection settings are correct, then restart.");
        assertThat(analysis.getCause()).isSameAs(coded);
    }

    @Test
    void isRegisteredSoSpringBootInvokesItOnStartupFailure() throws Exception {
        // Spring Boot discovers FailureAnalyzers through META-INF/spring.factories, not the
        // auto-configuration .imports mechanism; registering it the wrong way leaves startup failures
        // printing a raw stack trace. Assert the registration is present under the right key.
        String key = "org.springframework.boot.diagnostics.FailureAnalyzer";
        boolean registered = false;
        Enumeration<URL> resources = getClass().getClassLoader().getResources("META-INF/spring.factories");
        while (resources.hasMoreElements()) {
            Properties props = new Properties();
            try (InputStream in = resources.nextElement().openStream()) {
                props.load(in);
            }
            String value = props.getProperty(key);
            if (value != null && value.contains(CodedFailureAnalyzer.class.getName())) {
                registered = true;
                break;
            }
        }
        assertThat(registered).as("CodedFailureAnalyzer registered under the FailureAnalyzer key").isTrue();
    }
}
