package io.cyntex.control.restapi;

import io.cyntex.control.core.CredentialAuthenticator;
import io.cyntex.control.core.GeneratedSecret;
import io.cyntex.control.core.PipelineLogQueryService;
import io.cyntex.control.core.PipelineObservationQueryService;
import io.cyntex.control.core.Scope;
import io.cyntex.control.core.TokenSecrets;
import io.cyntex.control.core.TokenService;
import io.cyntex.control.core.TokenSigner;
import io.cyntex.control.core.VerifiedToken;
import io.cyntex.core.common.JsonReader;
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
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two thin streaming read faces projected onto a websocket: {@code /api/pipelines/{id}/status/watch}
 * and {@code /api/pipelines/{id}/logs/follow}. Each rides a per-connection poll of the same control-core
 * query service the one-shot {@code GET} uses; it is not a general push framework and adds no registry
 * operation — the read-scoped grade of the underlying {@code pipeline.status} / {@code pipeline.logs}
 * operation gates the handshake. This proves a state change reaches a watcher, a new log line reaches a
 * follower, and an unauthenticated handshake is refused. The context is booted programmatically over
 * fake, seedable stores; the poll interval is shortened so the assertions do not wait on the default.
 */
class PipelineStreamApiTest {

    private static ConfigurableApplicationContext context;
    private static int port;

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

    private String readToken() {
        return context.getBean(TokenService.class).create(Scope.READ);
    }

    private WebSocket connect(String path, String token, FrameSink sink) {
        return HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Authorization", "Bearer " + token)
                .buildAsync(URI.create("ws://localhost:" + port + path), sink)
                .join();
    }

    // ---- a watcher gets the current state, then the next state on change ----

    @Test
    void watchStreamsTheStateAndThenTheNextStateOnChange() throws Exception {
        FakeObservationStore observations = context.getBean(FakeObservationStore.class);
        observations.save(new Observation("pl1", PipelineState.RUNNING, null, null));

        FrameSink sink = new FrameSink();
        WebSocket ws = connect("/api/pipelines/pl1/status/watch", readToken(), sink);
        try {
            Map<?, ?> first = sink.nextFrame();
            assertThat(first.get("pipelineId")).isEqualTo("pl1");
            assertThat(first.get("state")).isEqualTo("RUNNING");

            observations.save(new Observation("pl1", PipelineState.PAUSED, null, null));

            Map<?, ?> second = sink.nextFrame();
            assertThat(second.get("state")).isEqualTo("PAUSED");
        } finally {
            ws.abort();
        }
    }

    // ---- a follower gets existing lines, then a newly appended one ----

    @Test
    void followStreamsTheTailAndThenNewlyAppendedLines() throws Exception {
        FakeLogSink sink = context.getBean(FakeLogSink.class);
        sink.append("pl2", new LogLine(1_700_000_000_000L, "INFO", "submitted job"));

        FrameSink frames = new FrameSink();
        WebSocket ws = connect("/api/pipelines/pl2/logs/follow", readToken(), frames);
        try {
            Map<?, ?> first = frames.nextFrame();
            assertThat(first.get("pipelineId")).isEqualTo("pl2");
            assertThat(messages(first)).containsExactly("submitted job");

            sink.append("pl2", new LogLine(1_700_000_000_100L, "INFO", "converged to RUNNING"));

            Map<?, ?> second = frames.nextFrame();
            assertThat(messages(second)).containsExactly("converged to RUNNING");
        } finally {
            ws.abort();
        }
    }

    // ---- the handshake is guarded like every other read ----

    @Test
    void anUnauthenticatedHandshakeIsRefusedUnauthorized() {
        assertThatThrownBy(() -> HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/api/pipelines/pl1/status/watch"), new FrameSink())
                .join())
                .hasCauseInstanceOf(WebSocketHandshakeException.class)
                .cause()
                .satisfies(cause -> assertThat(((WebSocketHandshakeException) cause).getResponse().statusCode())
                        .isEqualTo(401));
    }

    @SuppressWarnings("unchecked")
    private static List<String> messages(Map<?, ?> frame) {
        List<String> out = new ArrayList<>();
        for (Object line : (List<Object>) frame.get("lines")) {
            out.add((String) ((Map<?, ?>) line).get("message"));
        }
        return out;
    }

    // ---- a listener that decodes each text frame into a JSON map on a blocking queue ----

    static final class FrameSink implements WebSocket.Listener {
        private final LinkedBlockingQueue<Map<?, ?>> frames = new LinkedBlockingQueue<>();
        private final StringBuilder partial = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                frames.add((Map<?, ?>) JsonReader.parse(partial.toString()));
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        Map<?, ?> nextFrame() throws InterruptedException {
            Map<?, ?> frame = frames.poll(5, TimeUnit.SECONDS);
            assertThat(frame).as("a frame arrived within the timeout").isNotNull();
            return frame;
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({PipelineStreamConfiguration.class})
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
            return new TokenService(store, secrets, java.time.Clock.systemUTC());
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
