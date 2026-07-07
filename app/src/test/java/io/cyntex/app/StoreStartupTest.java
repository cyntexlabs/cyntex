package io.cyntex.app;

import io.cyntex.adapters.mongostore.MongoConnection;
import io.cyntex.adapters.mongostore.StoreError;
import io.cyntex.core.common.CyntexException;
import io.cyntex.spi.store.StorePort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The store connection is wired into startup: with it enabled and pointed at an unreachable
 * (plaintext, no flag) target, the context fails and the failure carries the {@code store.unreachable}
 * coded diagnostic (not a bare driver exception); disabled, the context starts with no store
 * connection at all. TLS is opt-in, so a plaintext URI connects without any downgrade flag.
 */
class StoreStartupTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StoreConfiguration.class);

    @Test
    void enabledButUnreachableFailsStartupWithACodedDiagnostic() {
        // A plaintext URI (no ssl) with no flag: TLS is opt-in, so the connection is attempted and a
        // dead port surfaces the store-unreachable coded diagnostic at startup.
        runner.withPropertyValues(
                        "cyntex.store.mongo.enabled=true",
                        "cyntex.store.mongo.uri=mongodb://localhost:1/cyntex",
                        "cyntex.store.mongo.server-selection-timeout=300ms")
                .run(context -> {
                    assertThat(context).hasFailed();
                    CyntexException coded = firstCauseOfType(context.getStartupFailure(), CyntexException.class);
                    assertThat(coded).as("startup failure carries a coded diagnostic").isNotNull();
                    assertThat(coded.code()).isEqualTo(StoreError.UNREACHABLE);
                });
    }

    @Test
    void disabledStartsWithoutAStoreConnectionOrPort() {
        runner.withPropertyValues("cyntex.store.mongo.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(MongoConnection.class);
                    // The StorePort is gated on the same switch: no store means no port either.
                    assertThat(context).doesNotHaveBean(StorePort.class);
                });
    }

    private static <T extends Throwable> T firstCauseOfType(Throwable failure, Class<T> type) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
        }
        return null;
    }
}
