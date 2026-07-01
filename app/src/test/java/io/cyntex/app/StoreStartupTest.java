package io.cyntex.app;

import io.cyntex.adapters.mongostore.MongoConnection;
import io.cyntex.adapters.mongostore.StoreError;
import io.cyntex.core.common.CyntexException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The store connection is wired into startup: with it enabled and pointed at an unreachable target,
 * the context fails and the failure carries the {@code store.unreachable} coded diagnostic (not a
 * bare driver exception); disabled, the context starts with no store connection at all.
 */
class StoreStartupTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StoreConfiguration.class);

    @Test
    void enabledButUnreachableFailsStartupWithACodedDiagnostic() {
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
    void disabledStartsWithoutAStoreConnection() {
        runner.withPropertyValues("cyntex.store.mongo.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(MongoConnection.class);
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
