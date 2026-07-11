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
                        "connection.test",
                        "cluster.members",
                        "pipeline.start",
                        "pipeline.stop",
                        "pipeline.pause",
                        "pipeline.resume",
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
        // connection.test persists its result for later query, so it is a state-mutating write.
        assertThat(registry.resolve("connection.test").scope()).isEqualTo(Scope.WRITE);
        // cluster.members reads live topology; it is authenticated like every registry operation, but
        // needs no write or admin privilege.
        assertThat(registry.resolve("cluster.members").scope()).isEqualTo(Scope.READ);
        // the four pipeline lifecycle verbs write desired state, so they are write-scoped.
        for (String id : List.of("pipeline.start", "pipeline.stop", "pipeline.pause", "pipeline.resume")) {
            assertThat(registry.resolve(id).scope()).as(id).isEqualTo(Scope.WRITE);
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
                        "connection.test",
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
        for (String id : List.of("artifact.get", "artifact.list", "cluster.members", "user.list", "token.list")) {
            assertThat(registry.resolve(id).audited()).as(id).isFalse();
        }
    }

    @Test
    void everyL1OperationIsOnTheCliPocSurface() {
        assertThat(registry.exposedOn(Frontend.CLI, Maturity.POC))
                .hasSize(15)
                .allSatisfy(op -> assertThat(op.exposure()).containsEntry(Frontend.CLI, Maturity.POC));
    }

    @Test
    void mcpAndRestFacesAreEmptyAtL1() {
        // L1 grows no MCP tool and no REST path: no operation carries such an exposure key, so the
        // derivation is empty even at the widest maturity ceiling.
        assertThat(registry.exposedOn(Frontend.MCP, Maturity.GA)).isEmpty();
        assertThat(registry.exposedOn(Frontend.REST, Maturity.GA)).isEmpty();
    }
}
