package io.cyntex.control.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ControlOperationsTest {

    private final OperationRegistry registry = ControlOperations.registry();

    @Test
    void registersExactlyTheL1OperationSet() {
        assertThat(registry.ids())
                .containsExactlyInAnyOrder(
                        "artifact.apply",
                        "artifact.get",
                        "artifact.list",
                        "source.create",
                        "source.list",
                        "source.get",
                        "source.update",
                        "source.delete",
                        "connection.test",
                        "connection.test-result",
                        "connection.discover-schema",
                        "connection.schema",
                        "connector.register",
                        "connector.list",
                        "cluster.members",
                        "pipeline.start",
                        "pipeline.stop",
                        "pipeline.pause",
                        "pipeline.resume",
                        "pipeline.status",
                        "pipeline.metrics",
                        "pipeline.snapshot",
                        "pipeline.logs",
                        "user.create",
                        "user.passwd",
                        "user.list",
                        "token.create",
                        "token.revoke",
                        "token.list");
    }

    @Test
    void scopesMatchTheOperationInventory() {
        assertThat(registry.resolve("artifact.apply").scope()).isEqualTo(Scope.WRITE);
        assertThat(registry.resolve("artifact.get").scope()).isEqualTo(Scope.READ);
        assertThat(registry.resolve("artifact.list").scope()).isEqualTo(Scope.READ);
        assertThat(registry.resolve("source.create").scope()).isEqualTo(Scope.WRITE);
        assertThat(registry.resolve("source.list").scope()).isEqualTo(Scope.READ);
        assertThat(registry.resolve("source.get").scope()).isEqualTo(Scope.READ);
        assertThat(registry.resolve("source.update").scope()).isEqualTo(Scope.WRITE);
        assertThat(registry.resolve("source.delete").scope()).isEqualTo(Scope.WRITE);
        // connection.test persists its result for later query, so it is a state-mutating write.
        assertThat(registry.resolve("connection.test").scope()).isEqualTo(Scope.WRITE);
        // connection.test-result reads back the latest persisted result; it mutates nothing, so it is read.
        assertThat(registry.resolve("connection.test-result").scope()).isEqualTo(Scope.READ);
        // connection.discover-schema persists the discovered source model for later query, so it is a
        // state-mutating write.
        assertThat(registry.resolve("connection.discover-schema").scope()).isEqualTo(Scope.WRITE);
        // connection.schema reads back the latest persisted source model; it mutates nothing, so it is read.
        assertThat(registry.resolve("connection.schema").scope()).isEqualTo(Scope.READ);
        // connector.register ingests a connector artifact into the distribution store, so it is a
        // state-mutating write.
        assertThat(registry.resolve("connector.register").scope()).isEqualTo(Scope.WRITE);
        // connector.list reads the online catalog view (bundled snapshot union registered rows); it
        // mutates nothing, so it is read.
        assertThat(registry.resolve("connector.list").scope()).isEqualTo(Scope.READ);
        // cluster.members reads live topology; it is authenticated like every registry operation, but
        // needs no write or admin privilege.
        assertThat(registry.resolve("cluster.members").scope()).isEqualTo(Scope.READ);
        // the four pipeline lifecycle verbs write desired state, so they are write-scoped.
        for (String id : List.of("pipeline.start", "pipeline.stop", "pipeline.pause", "pipeline.resume")) {
            assertThat(registry.resolve(id).scope()).as(id).isEqualTo(Scope.WRITE);
        }
        // the pipeline observation reads (status/metrics/snapshot store-backed, logs node-local) are all
        // read faces; read-scoped, unaudited.
        for (String id : List.of("pipeline.status", "pipeline.metrics", "pipeline.snapshot", "pipeline.logs")) {
            assertThat(registry.resolve(id).scope()).as(id).isEqualTo(Scope.READ);
        }
        for (String id : List.of("user.create", "user.passwd", "user.list", "token.create", "token.revoke", "token.list")) {
            assertThat(registry.resolve(id).scope()).as(id).isEqualTo(Scope.ADMIN);
        }
    }

    @Test
    void auditFlagMarksOnlyTheStateMutatingOperations() {
        for (String id :
                List.of(
                        "artifact.apply",
                        "source.create",
                        "source.update",
                        "source.delete",
                        "connection.test",
                        "connection.discover-schema",
                        "connector.register",
                        "pipeline.start",
                        "pipeline.stop",
                        "pipeline.pause",
                        "pipeline.resume",
                        "user.create",
                        "user.passwd",
                        "token.create",
                        "token.revoke")) {
            assertThat(registry.resolve(id).audited()).as(id).isTrue();
        }
        for (String id : List.of(
                "artifact.get",
                "artifact.list",
                "source.list",
                "source.get",
                "connection.test-result",
                "connection.schema",
                "connector.list",
                "cluster.members",
                "user.list",
                "token.list",
                "pipeline.status",
                "pipeline.metrics",
                "pipeline.snapshot",
                "pipeline.logs")) {
            assertThat(registry.resolve(id).audited()).as(id).isFalse();
        }
    }

    @Test
    void theRegistryOpensEveryL1OperationOnTheCliFaceAtPoc() {
        // a scope statement about the registry alone: L1 opens all 29 operations on the CLI face and
        // clips none of them below POC. Whether each one has a verb behind it is not knowable from here
        // — control-core cannot see the CLI — and is gated where both are visible, in arch-tests.
        assertThat(registry.exposedOn(Frontend.CLI, Maturity.POC)).hasSize(29);
        assertThat(registry.all()).allSatisfy(op ->
                assertThat(op.exposure()).as(op.id()).containsEntry(Frontend.CLI, Maturity.POC));
    }

    @Test
    void mcpAndRestFacesAreEmptyAtL1() {
        // L1 grows no MCP tool and no REST path: no operation carries such an exposure key, so the
        // derivation is empty even at the widest maturity ceiling.
        assertThat(registry.exposedOn(Frontend.MCP, Maturity.GA)).isEmpty();
        assertThat(registry.exposedOn(Frontend.REST, Maturity.GA)).isEmpty();
    }
}
