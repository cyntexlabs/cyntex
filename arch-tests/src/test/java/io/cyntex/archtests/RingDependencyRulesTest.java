package io.cyntex.archtests;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Package-level enforcement of the ring dependency rules (the fine-grained gate; the
 * enforcer bannedDependencies allowlist in the ring parents is the coarse-grained one).
 *
 * <p>Rings that do not exist yet (spi / cli ...) use {@code allowEmptyShould(true)}:
 * the rule idles while the packages are empty and becomes effective automatically as
 * soon as the first class appears - no test change needed.
 */
class RingDependencyRulesTest {

    private static JavaClasses cyntexClasses;

    @BeforeAll
    static void importCyntexClasses() {
        cyntexClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.cyntex");
    }

    @Test
    @DisplayName("R1: core ring (except core-dsl) depends only on java.., itself, and jackson-annotations")
    void r1_coreRingDependsOnWhitelistOnly() {
        noClasses().that().resideInAPackage("io.cyntex.core..")
                // core-dsl carries its own additional grant (see r1_coreDslAlsoAllowsYamlParser);
                // every other core module is held to the zero-framework allowlist
                .and().resideOutsideOfPackage("io.cyntex.core.dsl..")
                .should().dependOnClassesThat().resideOutsideOfPackages(
                        "java..",
                        "io.cyntex.core..",
                        // annotations only, no runtime behavior
                        "com.fasterxml.jackson.annotation.."
                )
                .allowEmptyShould(true)
                .because("the core ring depends on no other ring; third-party dependencies are "
                        + "individually named, anything outside the allowlist is a red light")
                .check(cyntexClasses);
    }

    @Test
    @DisplayName("R1 (core-dsl grant): core-dsl adds the YAML parser and the CEL compiler, nothing more")
    void r1_coreDslAlsoAllowsYamlParserAndCel() {
        noClasses().that().resideInAPackage("io.cyntex.core.dsl..")
                .should().dependOnClassesThat().resideOutsideOfPackages(
                        "java..",
                        "io.cyntex.core..",
                        "com.fasterxml.jackson.annotation..",
                        // R1 named grants, core-dsl-only: the YAML parser (B3) and the CEL
                        // expression compiler (B4). cel-java's transitive libraries (guava /
                        // protobuf / antlr4) stay out of core-dsl's own bytecode surface.
                        "org.yaml.snakeyaml..",
                        "dev.cel.."
                )
                .allowEmptyShould(true)
                .because("the YAML parser and CEL compiler are granted to core-dsl alone; the rest "
                        + "of the core ring still bans them (enforcer pom grant is the coarse twin "
                        + "of this rule)")
                .check(cyntexClasses);
    }

    @Test
    @DisplayName("R2: spi ring depends only on core")
    void r2_spiRingOnlyDependsOnCore() {
        classes().that().resideInAPackage("io.cyntex.spi..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "java..",
                        "io.cyntex.spi..",
                        "io.cyntex.core..")
                .allowEmptyShould(true)
                .because("ports depend one-way on the kernel and on nothing else")
                .check(cyntexClasses);
    }

    @Test
    @DisplayName("R6: cli depends only on the core ring + the shared message catalog")
    void r6_cliOnlyDependsOnCoreRing() {
        classes().that().resideInAPackage("io.cyntex.cli..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "java..",
                        "io.cyntex.cli..",
                        "io.cyntex.core..",
                        // the shared error-code message catalog + renderer (presentation layer)
                        "io.cyntex.messages..",
                        // the CLI's own facade libraries
                        "picocli..",
                        "org.jline..")
                .allowEmptyShould(true)
                .because("the CLI talks to services over HTTP only; it must have no compile "
                        + "dependency on control or runtime modules; the shared message catalog is "
                        + "a presentation-layer leaf, not a service ring")
                .check(cyntexClasses);
    }

    @Test
    @DisplayName("messages (shared presentation catalog) depends only on the core ring")
    void messagesModuleDependsOnCoreRingOnly() {
        classes().that().resideInAPackage("io.cyntex.messages..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "java..",
                        "io.cyntex.messages..",
                        "io.cyntex.core..")
                .allowEmptyShould(true)
                .because("the shared message catalog renders coded errors for every presentation "
                        + "face; it depends only on the error-code contract in the core ring and "
                        + "carries no third-party, so no face inherits a library through it")
                .check(cyntexClasses);
    }

    @Test
    @DisplayName("R3: adapters never reach up into runtime / control / surface rings")
    void r3_adaptersDoNotDependOnHigherRings() {
        // Adapters depend one-way on the ports and the kernel. Their third-party system
        // dependencies are open-ended (PDK / Mongo driver / future backends) and locked
        // per-module below, so the ring-level rule is expressed as a ban on upward edges
        // rather than a positive package allowlist.
        noClasses().that().resideInAPackage("io.cyntex.adapters..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.cyntex.runtime..",
                        "io.cyntex.control..",
                        "io.cyntex.cli..",
                        "io.cyntex.app..")
                .allowEmptyShould(true)
                .because("adapters depend one-way on the ports and the kernel only; they never "
                        + "reach up into the runtime, control, or surface rings")
                .check(cyntexClasses);
    }

    @Test
    @DisplayName("R3 (PDK lock): only adapter-pdk may import the PDK API")
    void r3_pdkLockedToAdapterPdk() {
        noClasses().that().resideOutsideOfPackage("io.cyntex.adapters.pdk..")
                .should().dependOnClassesThat().resideInAPackage("io.tapdata..")
                .allowEmptyShould(true)
                .because("the PDK API is locked to adapter-pdk; no other module may import it")
                .check(cyntexClasses);
    }

    @Test
    @DisplayName("R3 (Mongo lock): only adapter-mongo-store may depend on the Mongo driver")
    void r3_mongoDriverLockedToAdapterMongoStore() {
        noClasses().that().resideOutsideOfPackage("io.cyntex.adapters.mongostore..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.mongodb..",
                        "org.bson..")
                .allowEmptyShould(true)
                .because("the Mongo driver is locked to adapter-mongo-store; no other module may "
                        + "depend on it")
                .check(cyntexClasses);
    }

    @Test
    @DisplayName("R4: runtime depends on core + spi (+ Hazelcast) only, never on adapters")
    void r4_runtimeRingLayering() {
        classes().that().resideInAPackage("io.cyntex.runtime..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "java..",
                        "io.cyntex.runtime..",
                        "io.cyntex.spi..",
                        "io.cyntex.core..",
                        // the runtime's execution substrate (the Hazelcast fork)
                        "com.hazelcast..")
                .allowEmptyShould(true)
                .because("the runtime depends on the ports and the kernel (and Hazelcast) only; "
                        + "adapters are injected by the app assembly root, never compiled in")
                .check(cyntexClasses);
    }

    @Test
    @DisplayName("R5: control-core depends on core + the storage port + the connection-probe seam only (framework-free — no Spring)")
    void r5_controlCoreLayering() {
        classes().that().resideInAPackage("io.cyntex.control.core..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "java..",
                        "io.cyntex.control.core..",
                        "io.cyntex.core..",
                        // control-core decouples from the runtime through the storage port
                        "io.cyntex.spi.store..",
                        // the synchronous control-to-runtime seam: the probe whitelist (a closed set
                        // of two — the connection probe and the schema-discovery probe). Every other
                        // control<->runtime interaction stays store-decoupled; this narrow channel is
                        // the one compile reference control-core holds into the runtime ring
                        "io.cyntex.runtime.probe..")
                .allowEmptyShould(true)
                .because("control-core is the resource-type-agnostic verb layer: pure logic that "
                        + "depends on the kernel, the storage port, and the synchronous probe "
                        + "whitelist only. It stays framework-free — Spring lives in "
                        + "rest-api (the HTTP presentation face), never here — so the apply / registry "
                        + "logic is unit-testable without a container; it reaches the runtime only "
                        + "through the store, save for the probe whitelist (a closed set of two)")
                .check(cyntexClasses);
    }

    @Test
    @DisplayName("R5: rest-api depends on control-core + core + the shared message catalog (not the ports)")
    void r5_restApiLayering() {
        classes().that().resideInAPackage("io.cyntex.control.restapi..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "java..",
                        "io.cyntex.control.restapi..",
                        "io.cyntex.control.core..",
                        "io.cyntex.core..",
                        // the shared error-code message catalog + renderer (presentation layer): rest-api
                        // renders coded errors the same way the CLI does (its R6 grant), a leaf not a ring
                        "io.cyntex.messages..",
                        // Spring is permitted in the control ring (rest-api is the HTTP layer)
                        "org.springframework..",
                        // Jackson annotations and databind are the JSON substrate used only by the HTTP
                        // projection to enforce request shape and response omission rules.
                        "com.fasterxml.jackson.annotation..",
                        "tools.jackson.databind..",
                        // the Servlet API is the substrate the Web MVC servlet stack runs on; the HTTP
                        // layer's interceptor and controllers read the request through it
                        "jakarta.servlet..")
                .allowEmptyShould(true)
                .because("the HTTP presentation adapter sits on control-core, the kernel, the shared "
                        + "message catalog and the servlet substrate; it does not reach the ports directly")
                .check(cyntexClasses);
    }

    @Test
    @DisplayName("R5 (exactness): the control-to-runtime sync whitelist is exactly the connection probe "
            + "and the schema-discovery probe — no further channel")
    void r5_controlToRuntimeSyncWhitelistHasNoFurtherChannel() {
        // A control-to-runtime sync channel is a runtime interface control reaches for. The whitelist is
        // a closed set of exactly two such interfaces (the connection probe and the schema-discovery
        // probe); the probes' value types are storage-port types (the connection config they take, the
        // test result / source model they return), carried as payload, not channels of their own. This
        // gate bans a further channel — another probe interface control depends on. Widening the
        // whitelist must change this gate and the sync-whitelist decision, not slip in beside it (a
        // further probe interface control reaches for turns this red).
        DescribedPredicate<JavaClass> aRuntimeSyncChannelOutsideTheWhitelist =
                resideInAPackage("io.cyntex.runtime.probe..")
                        .and(DescribedPredicate.describe("interfaces", JavaClass::isInterface))
                        .and(DescribedPredicate.not(name("io.cyntex.runtime.probe.ConnectionProbe")))
                        .and(DescribedPredicate.not(name("io.cyntex.runtime.probe.SchemaDiscoveryProbe")))
                        .as("a control-to-runtime sync channel outside the whitelist");
        noClasses().that().resideInAPackage("io.cyntex.control..")
                .should().dependOnClassesThat(aRuntimeSyncChannelOutsideTheWhitelist)
                .allowEmptyShould(true)
                .because("the control-to-runtime sync whitelist is a closed set of exactly two members, "
                        + "the connection probe and the schema-discovery probe; a further synchronous "
                        + "channel must change this gate and the sync-whitelist decision, not slip in "
                        + "beside it")
                .check(cyntexClasses);
    }

    @Test
    @DisplayName("R5 (exactness): each whitelisted probe exposes exactly one operation")
    void r5_eachWhitelistedProbeExposesExactlyOneOperation() {
        // Each whitelist member is a single operation. Counting all abstract methods — including any
        // inherited from a super-interface, not only the probe's own — a second operation (added
        // directly or pulled in through a super-interface) is a further synchronous control-to-runtime
        // call and must change the sync-whitelist decision, not slip in.
        for (String probeName : new String[] {
            "io.cyntex.runtime.probe.ConnectionProbe", "io.cyntex.runtime.probe.SchemaDiscoveryProbe"
        }) {
            JavaClass probe = cyntexClasses.get(probeName);
            long operations = probe.getAllMethods().stream()
                    .filter(method -> method.getModifiers().contains(JavaModifier.ABSTRACT))
                    .count();
            assertThat(operations)
                    .as("each whitelisted probe is a closed set of exactly one operation: " + probeName)
                    .isEqualTo(1L);
        }
    }

    @Test
    @DisplayName("R5 (exactness): control-core never drives the spi execution ports directly — the probes "
            + "are the only path")
    void r5_controlCoreDoesNotBypassTheProbesIntoTheSpiExecutionPorts() {
        // The ring grant allows control-core to see all of io.cyntex.spi.store (it holds the storage
        // ports), and the spi execution ports — the connection tester and the schema discoverer, the
        // ports the probes delegate to — live in that same package. Compiling against them from
        // control-core would silently bypass the runtime seam: legal to the package rule above, but a
        // reversal of the sync-whitelist decision (discovery and testing run where the connectors run —
        // the runtime side). This gate turns that bypass red instead of leaving it to prose.
        DescribedPredicate<JavaClass> anSpiExecutionPort = name("io.cyntex.spi.store.ConnectionTester")
                .or(name("io.cyntex.spi.store.SchemaDiscoverer"))
                .<JavaClass>forSubtype()
                .as("an spi execution port (the connection tester / the schema discoverer)");
        noClasses().that().resideInAPackage("io.cyntex.control..")
                .should().dependOnClassesThat(anSpiExecutionPort)
                .allowEmptyShould(true)
                .because("control drives connection testing and schema discovery only through the "
                        + "whitelisted runtime probes; a direct compile reference to the spi execution "
                        + "ports bypasses the seam and reverses the sync-whitelist decision")
                .check(cyntexClasses);
    }

    @Test
    @DisplayName("R9: control and runtime hold no compile reference to each other, save the connection-probe whitelist")
    void r9_controlAndRuntimeDoNotReferenceEachOther() {
        // The control-to-runtime half carries the single R5 exception: control may reach the runtime
        // synchronously only through the probe whitelist (io.cyntex.runtime.probe), a closed set of
        // two. Any other runtime package is still forbidden; the exactness gates above pin that
        // whitelist to exactly two channels of one operation each.
        noClasses().that().resideInAPackage("io.cyntex.control..")
                .should().dependOnClassesThat(
                        JavaClass.Predicates.resideInAPackage("io.cyntex.runtime..")
                                .and(JavaClass.Predicates.resideOutsideOfPackage("io.cyntex.runtime.probe..")))
                .allowEmptyShould(true)
                .because("control writes desired state and the runtime watches and converges; they "
                        + "decouple through the store and hold no reference to each other — the sole "
                        + "exception is the synchronous probe whitelist "
                        + "(io.cyntex.runtime.probe), a closed set of two")
                .check(cyntexClasses);
        // The runtime-to-control half stays a blanket ban: the runtime never reaches up into control.
        noClasses().that().resideInAPackage("io.cyntex.runtime..")
                .should().dependOnClassesThat().resideInAPackage("io.cyntex.control..")
                .allowEmptyShould(true)
                .because("control writes desired state and the runtime watches and converges; the "
                        + "runtime holds no reference back into the control ring")
                .check(cyntexClasses);
    }

    @Test
    @DisplayName("R7: the app assembly root is the only non-adapter module allowed to depend on adapters")
    void r7_onlyAppMayDependOnAdapters() {
        noClasses().that().resideOutsideOfPackages("io.cyntex.app..", "io.cyntex.adapters..")
                .should().dependOnClassesThat().resideInAPackage("io.cyntex.adapters..")
                .allowEmptyShould(true)
                .because("the app assembly root is the single place that wires adapters into the "
                        + "runtime (the conditional --role loading point)")
                .check(cyntexClasses);
    }
}
