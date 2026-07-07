package io.cyntex.control.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class OperationRegistryTest {

    private static Operation op(String id, Scope scope, Map<Frontend, Maturity> exposure) {
        return new Operation(id, scope, scope != Scope.READ, null, exposure);
    }

    @Test
    void dispatchGuard_rejectsUnregisteredOperationId() {
        OperationRegistry registry =
                OperationRegistry.of(op("artifact.apply", Scope.WRITE, Map.of(Frontend.CLI, Maturity.POC)));

        assertThatThrownBy(() -> registry.resolve("artifact.nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("artifact.nope");
    }

    @Test
    void dispatchGuard_resolvesRegisteredOperationId() {
        Operation apply = op("artifact.apply", Scope.WRITE, Map.of(Frontend.CLI, Maturity.POC));
        OperationRegistry registry = OperationRegistry.of(apply);

        assertThat(registry.resolve("artifact.apply")).isEqualTo(apply);
        assertThat(registry.isRegistered("artifact.apply")).isTrue();
        assertThat(registry.find("artifact.nope")).isEmpty();
    }

    @Test
    void build_rejectsDuplicateOperationId() {
        Operation a = op("artifact.apply", Scope.WRITE, Map.of(Frontend.CLI, Maturity.POC));
        Operation b = op("artifact.apply", Scope.READ, Map.of(Frontend.MCP, Maturity.BETA));

        assertThatThrownBy(() -> OperationRegistry.of(a, b))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("artifact.apply");
    }

    @Test
    void exposedOn_derivesFrontendSurfaceByMaturityCeiling() {
        Operation apply = op("artifact.apply", Scope.WRITE, Map.of(Frontend.CLI, Maturity.POC));
        Operation validate = op(
                "artifact.validate", Scope.READ, Map.of(Frontend.CLI, Maturity.POC, Frontend.MCP, Maturity.ALPHA));
        Operation register = op("connector.register", Scope.ADMIN, Map.of(Frontend.MCP, Maturity.BETA));
        OperationRegistry registry = OperationRegistry.of(apply, validate, register);

        // CLI surface at the POC ceiling: only ops exposed on CLI with maturity <= POC.
        assertThat(registry.exposedOn(Frontend.CLI, Maturity.POC)).containsExactlyInAnyOrder(apply, validate);
        // MCP surface at the ALPHA ceiling: validate (ALPHA) is in, register (BETA) is out, apply (no MCP) is out.
        assertThat(registry.exposedOn(Frontend.MCP, Maturity.ALPHA)).containsExactly(validate);
        // MCP surface at the GA ceiling widens to include the BETA op.
        assertThat(registry.exposedOn(Frontend.MCP, Maturity.GA)).containsExactlyInAnyOrder(validate, register);
    }

    @Test
    void exposedOn_rejectsNullArgumentsConsistently() {
        // Only CLI is exposed; a null ceiling must fail the same way whether or not the face has an op,
        // instead of NPE-ing on the exposed face and silently returning empty on the unexposed one.
        OperationRegistry registry =
                OperationRegistry.of(op("artifact.apply", Scope.WRITE, Map.of(Frontend.CLI, Maturity.POC)));

        assertThatThrownBy(() -> registry.exposedOn(Frontend.MCP, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.exposedOn(Frontend.CLI, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.exposedOn(null, Maturity.POC)).isInstanceOf(NullPointerException.class);
    }
}
