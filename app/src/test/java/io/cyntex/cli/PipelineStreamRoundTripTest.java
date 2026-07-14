package io.cyntex.cli;

import io.cyntex.control.core.CredentialAuthenticator;
import io.cyntex.control.core.GeneratedSecret;
import io.cyntex.control.core.PipelineLogQueryService;
import io.cyntex.control.core.PipelineObservationQueryService;
import io.cyntex.control.core.Scope;
import io.cyntex.control.core.TokenSecrets;
import io.cyntex.control.core.TokenService;
import io.cyntex.control.core.TokenSigner;
import io.cyntex.control.core.VerifiedToken;
import io.cyntex.control.restapi.PipelineStreamConfiguration;
import io.cyntex.core.lifecycle.Observation;
import io.cyntex.core.lifecycle.PipelineState;
import io.cyntex.core.logging.LogLine;
import io.cyntex.core.logging.LogSink;
import io.cyntex.spi.store.ObservationStore;
import io.cyntex.spi.store.TokenRecord;
import io.cyntex.spi.store.TokenStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real cli-client to server websocket round-trip: the production {@link HttpControlPlaneClient}'s
 * streaming half — its connect loop and frame reassembly — driven against a real server, closing the gap
 * the server-side stream test leaves (it drives the wire with a test listener, not the production client).
 * A status change reaches the client's status stream and a newly appended log line reaches its log stream,
 * through the same code the REPL runs. The server is booted over fake, seedable stores and a shortened poll
 * so the assertions do not wait on the default cadence; the CLI client is a test-scope dependency here, so
 * the assembly root never ships it.
 */
class PipelineStreamRoundTripTest {

    private static ConfigurableApplicationContext context;
    private static int port;

    private final HttpControlPlaneClient client = new HttpControlPlaneClient();

    @BeforeAll
    static void startServer() {
        context = new SpringApplicationBuilder(TestApp.class)
                .properties("server.port=0", "cyntex.control.stream.poll-interval=PT0.05S")
                .run();
        port = ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (context != null) {
            context.close();
        }
    }

    @BeforeEach
    void resetStores() {
        context.getBean(FakeObservationStore.class).clear();
        context.getBean(FakeLogSink.class).clear();
    }

    private URI baseUrl() {
        return URI.create("http://localhost:" + port);
    }

    private String readToken() {
        return context.getBean(TokenService.class).create(Scope.READ);
    }

    @Test
    @DisplayName("watchStatus delivers the current state then the next state on change")
    void watchStatusDeliversTheCurrentStateThenTheChange() throws Exception {
        FakeObservationStore observations = context.getBean(FakeObservationStore.class);
        observations.save(new Observation("pl1", PipelineState.RUNNING, null, null));

        BlockingQueue<String> states = new LinkedBlockingQueue<>();
        AtomicBoolean stop = new AtomicBoolean(false);
        Thread watcher = new Thread(() -> client.watchStatus(
                baseUrl(), readToken(), "pl1", (id, state) -> states.add(state), stop::get));
        watcher.setDaemon(true);
        watcher.start();
        try {
            assertThat(states.poll(5, TimeUnit.SECONDS)).isEqualTo("RUNNING");

            observations.save(new Observation("pl1", PipelineState.PAUSED, null, null));

            assertThat(states.poll(5, TimeUnit.SECONDS)).isEqualTo("PAUSED");
        } finally {
            stop.set(true);
            watcher.join(TimeUnit.SECONDS.toMillis(3));
        }
    }

    @Test
    @DisplayName("followLogs delivers the existing tail then a newly appended line")
    void followLogsDeliversTheTailThenNewLines() throws Exception {
        FakeLogSink logs = context.getBean(FakeLogSink.class);
        logs.append("pl2", new LogLine(1_700_000_000_000L, "INFO", "submitted job"));

        BlockingQueue<List<RemoteLogLine>> batches = new LinkedBlockingQueue<>();
        AtomicBoolean stop = new AtomicBoolean(false);
        Thread follower = new Thread(() -> client.followLogs(
                baseUrl(), readToken(), "pl2", (id, lines) -> batches.add(lines), stop::get));
        follower.setDaemon(true);
        follower.start();
        try {
            List<RemoteLogLine> first = batches.poll(5, TimeUnit.SECONDS);
            assertThat(first).extracting(RemoteLogLine::message).containsExactly("submitted job");

            logs.append("pl2", new LogLine(1_700_000_000_100L, "INFO", "converged to RUNNING"));

            List<RemoteLogLine> second = batches.poll(5, TimeUnit.SECONDS);
            assertThat(second).extracting(RemoteLogLine::message).containsExactly("converged to RUNNING");
        } finally {
            stop.set(true);
            follower.join(TimeUnit.SECONDS.toMillis(3));
        }
    }

    /**
     * A focused boot of the streaming server half: the public stream configuration over the real query
     * services, guarded by the real credential authenticator, all over fake seedable stores.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(PipelineStreamConfiguration.class)
    static class TestApp {

        @Bean
        FakeObservationStore observationStore() {
            return new FakeObservationStore();
        }

        @Bean
        FakeLogSink logSink() {
            return new FakeLogSink();
        }

        @Bean
        PipelineObservationQueryService pipelineObservationQueryService(ObservationStore store) {
            return new PipelineObservationQueryService(store);
        }

        @Bean
        PipelineLogQueryService pipelineLogQueryService(LogSink sink) {
            return new PipelineLogQueryService(sink);
        }

        @Bean
        FakeTokenStore tokenStore() {
            return new FakeTokenStore();
        }

        @Bean
        TokenSecrets tokenSecrets() {
            return new FakeTokenSecrets();
        }

        @Bean
        TokenSigner tokenSigner() {
            return new FakeSigner();
        }

        @Bean
        TokenService tokenService(TokenStore store, TokenSecrets secrets) {
            return new TokenService(store, secrets, Clock.systemUTC());
        }

        @Bean
        CredentialAuthenticator credentialAuthenticator(TokenService tokens, TokenSigner signer) {
            return new CredentialAuthenticator(tokens, signer);
        }
    }

    // ---- fakes ----

    static final class FakeObservationStore implements ObservationStore {
        private final Map<String, Observation> byId = new LinkedHashMap<>();

        void clear() {
            byId.clear();
        }

        @Override
        public void save(Observation observation) {
            byId.put(observation.pipelineId(), observation);
        }

        @Override
        public Optional<Observation> read(String pipelineId) {
            return Optional.ofNullable(byId.get(pipelineId));
        }
    }

    static final class FakeLogSink implements LogSink {
        private final Map<String, List<LogLine>> byId = new LinkedHashMap<>();

        void clear() {
            byId.clear();
        }

        @Override
        public void append(String pipelineId, LogLine line) {
            byId.computeIfAbsent(pipelineId, k -> new ArrayList<>()).add(line);
        }

        @Override
        public List<LogLine> tail(String pipelineId) {
            return List.copyOf(byId.getOrDefault(pipelineId, List.of()));
        }
    }

    static final class FakeTokenStore implements TokenStore {
        private final Map<String, TokenRecord> byId = new LinkedHashMap<>();

        @Override
        public void save(TokenRecord record) {
            byId.put(record.tokenId(), record);
        }

        @Override
        public Optional<TokenRecord> find(String tokenId) {
            return Optional.ofNullable(byId.get(tokenId));
        }

        @Override
        public void revoke(String tokenId) {
            TokenRecord existing = byId.get(tokenId);
            if (existing != null) {
                byId.put(tokenId, new TokenRecord(existing.tokenId(), existing.scope(),
                        existing.secretHash(), true, existing.createdAt()));
            }
        }

        @Override
        public List<TokenRecord> list() {
            return new ArrayList<>(byId.values());
        }
    }

    static final class FakeTokenSecrets implements TokenSecrets {
        private int counter;

        @Override
        public GeneratedSecret generate() {
            counter++;
            return new GeneratedSecret("tok-" + counter, "sec-" + counter, "hash:sec-" + counter);
        }

        @Override
        public boolean matches(String presentedSecret, String storedHash) {
            return storedHash.equals("hash:" + presentedSecret);
        }
    }

    static final class FakeSigner implements TokenSigner {
        @Override
        public String issue(String subject, Scope scope) {
            return subject + "|" + scope.name();
        }

        @Override
        public Optional<VerifiedToken> verify(String token) {
            int bar = token.indexOf('|');
            if (bar < 0) {
                return Optional.empty();
            }
            return Optional.of(new VerifiedToken(token.substring(0, bar), Scope.valueOf(token.substring(bar + 1))));
        }
    }
}
