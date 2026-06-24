package io.cyntex.archtests;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

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
    @DisplayName("R2: spi ring depends only on core (placeholder; idle until spi modules exist)")
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
    @DisplayName("R6: cli depends only on the core ring (idle until the cli module exists)")
    void r6_cliOnlyDependsOnCoreRing() {
        classes().that().resideInAPackage("io.cyntex.cli..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "java..",
                        "io.cyntex.cli..",
                        "io.cyntex.core..",
                        // the CLI's own facade libraries
                        "picocli..",
                        "org.jline..")
                .allowEmptyShould(true)
                .because("the CLI talks to services over HTTP only; it must have no compile "
                        + "dependency on control or runtime modules")
                .check(cyntexClasses);
    }
}
