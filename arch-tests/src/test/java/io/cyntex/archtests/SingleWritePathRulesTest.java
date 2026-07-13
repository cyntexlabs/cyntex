package io.cyntex.archtests;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.cyntex.spi.store.DesiredStore;
import io.cyntex.spi.store.StateStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The pipeline lifecycle has a single write path on each side of the desired/actual split, and this
 * gate pins it: desired intent is written only through the control layer's one lifecycle service, and
 * actual state is written only through the runtime's one converge loop. No second control layer and no
 * second converger may write the store — a bypassing writer turns this red rather than quietly forking
 * the write path.
 */
class SingleWritePathRulesTest {

    private static JavaClasses cyntexClasses;

    /** A call that writes actual pipeline state: {@code StateStore.compareAndSwap} or {@code create}. */
    private static final DescribedPredicate<JavaMethodCall> WRITES_ACTUAL_STATE =
            new DescribedPredicate<>("a call that writes actual pipeline state") {
                @Override
                public boolean test(JavaMethodCall call) {
                    return call.getTargetOwner().getName().equals(StateStore.class.getName())
                            && (call.getName().equals("compareAndSwap") || call.getName().equals("create"));
                }
            };

    /** A call that writes desired pipeline intent: {@code DesiredStore.save}. */
    private static final DescribedPredicate<JavaMethodCall> WRITES_DESIRED_INTENT =
            new DescribedPredicate<>("a call that writes desired pipeline intent") {
                @Override
                public boolean test(JavaMethodCall call) {
                    return call.getTargetOwner().getName().equals(DesiredStore.class.getName())
                            && call.getName().equals("save");
                }
            };

    @BeforeAll
    static void importCyntexClasses() {
        cyntexClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.cyntex");
    }

    @Test
    @DisplayName("actual pipeline state is written only by the runtime converge loop")
    void actualStateIsWrittenOnlyByTheConvergeLoop() {
        noClasses().that().resideOutsideOfPackage("io.cyntex.runtime.scheduler..")
                .should().callMethodWhere(WRITES_ACTUAL_STATE)
                .allowEmptyShould(true)
                .because("actual state lands only through the single converge loop's fencing write and "
                        + "its seed; no second writer may compare-and-swap or create the actual-state store")
                .check(cyntexClasses);
    }

    @Test
    @DisplayName("desired pipeline intent is written only by the control lifecycle service")
    void desiredIntentIsWrittenOnlyByTheControlLayer() {
        noClasses().that().resideOutsideOfPackage("io.cyntex.control.core..")
                .should().callMethodWhere(WRITES_DESIRED_INTENT)
                .allowEmptyShould(true)
                .because("desired intent is written only through the control layer's one lifecycle "
                        + "service, the operation registry's single audited write path; no second "
                        + "control layer may write desired state")
                .check(cyntexClasses);
    }
}
