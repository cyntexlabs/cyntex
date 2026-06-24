package io.cyntex.archtests;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.cyntex.core.common.CyntexErrorCode;
import io.cyntex.core.common.Domain;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The error-code build gates (ADR-0024 D5) — the mono-repo replacement for the legacy "maintain a
 * spreadsheet" myth. One reactor build sees every first-party code, so global uniqueness / format /
 * registered-domain / stability become a build-time assertion instead of human discipline.
 *
 * <p>Scanning reuses arch-tests' existing capability (ArchUnit's {@code ClassFileImporter} to find
 * the enums, then {@code values()} via reflection) — no second scanning library (ADR-0024 D5-6).
 * Production scope only ({@code DO_NOT_INCLUDE_TESTS}), so throwaway test enums never leak in.
 *
 * <p>The connector-code namespace ({@code connector.* / pdk.*}) is deliberately out of scope: those
 * are runtime-loaded jars the build cannot see, guarded at runtime instead (ADR-0024 D6).
 */
class ErrorCodeGatesTest {

    /** <domain>.<symbol>, both lower-case kebab-case; symbol carries no further dot. */
    private static final Pattern SEGMENT = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");

    /** A named {@code {placeholder}} reference inside a catalog message template. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z0-9_]+)}");

    /** The mandatory locale every first-party code must be documented in. */
    private static final String CATALOG_RESOURCE = "/messages/en.yml";

    private static List<CyntexErrorCode> codes;

    /** Bundled en catalog: canonical code -> {message, [solution]} (the templates, not rendered). */
    private static Map<String, Map<String, String>> catalog;

    @BeforeAll
    static void scanFirstPartyCodes() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.cyntex");
        List<CyntexErrorCode> found = new ArrayList<>();
        for (JavaClass jc : classes) {
            if (jc.isEnum() && jc.isAssignableTo(CyntexErrorCode.class)) {
                for (Object constant : jc.reflect().getEnumConstants()) {
                    found.add((CyntexErrorCode) constant);
                }
            }
        }
        codes = found;
    }

    @BeforeAll
    static void loadBundledCatalog() {
        try (InputStream in = ErrorCodeGatesTest.class.getResourceAsStream(CATALOG_RESOURCE)) {
            assertThat(in).as("bundled message catalog %s on the test classpath", CATALOG_RESOURCE).isNotNull();
            Map<String, Object> raw = new Yaml().load(in);
            Map<String, Map<String, String>> parsed = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                Map<?, ?> body = (Map<?, ?>) e.getValue();
                Map<String, String> entry = new LinkedHashMap<>();
                Object message = body.get("message");
                Object solution = body.get("solution");
                if (message != null) {
                    entry.put("message", message.toString());
                }
                if (solution != null) {
                    entry.put("solution", solution.toString());
                }
                parsed.put(e.getKey(), entry);
            }
            catalog = parsed;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Test
    @DisplayName("the scan actually finds codes (else every gate below is vacuously green)")
    void scanIsNotEmpty() {
        assertThat(codes).isNotEmpty();
    }

    @Test
    @DisplayName("D5-1: every canonical code is globally unique across all enums and modules")
    void everyCanonicalCodeIsUnique() {
        Map<String, Long> byCode = codes.stream().collect(groupingBy(CyntexErrorCode::code, counting()));
        List<String> duplicates = byCode.entrySet().stream()
                .filter(e -> e.getValue() > 1).map(Map.Entry::getKey).sorted().toList();
        assertThat(duplicates)
                .as("canonical codes claimed by more than one constant")
                .isEmpty();
    }

    @Test
    @DisplayName("D5-2: every code is <domain>.<symbol> kebab-case with a registered domain")
    void everyCodeMatchesFormatAndRegisteredDomain() {
        for (CyntexErrorCode c : codes) {
            String code = c.code();
            int dot = code.indexOf('.');
            assertThat(dot).as("code '%s' must be <domain>.<symbol>", code).isPositive();
            String domain = code.substring(0, dot);
            String symbol = code.substring(dot + 1);
            assertThat(SEGMENT.matcher(domain).matches()).as("domain part of '%s'", code).isTrue();
            assertThat(SEGMENT.matcher(symbol).matches())
                    .as("symbol part of '%s' (lower-case kebab, no nested dot)", code).isTrue();
            assertThat(Domain.isRegistered(domain))
                    .as("domain '%s' of code '%s' is not in the Domain registry", domain, code).isTrue();
        }
    }

    @Test
    @DisplayName("D5-5: the canonical code set matches the checked-in golden (stability lock)")
    void canonicalCodesMatchGolden() throws IOException {
        List<String> scanned = codes.stream().map(CyntexErrorCode::code).distinct().sorted().toList();
        Path goldenFile = Path.of("src", "test", "resources", "error-codes.golden");
        List<String> golden = Files.readAllLines(goldenFile).stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
        assertThat(scanned)
                .as("canonical codes drifted from error-codes.golden — a new code appends to the "
                        + "golden in the same change set; a rename/removal is a breaking contract "
                        + "change (review + golden edit)")
                .isEqualTo(golden);
    }

    @Test
    @DisplayName("D5-3: every first-party code has an en catalog entry with a message")
    void everyCodeHasAnEnglishCatalogEntry() {
        List<String> missing = codes.stream()
                .map(CyntexErrorCode::code)
                .distinct()
                .sorted()
                .filter(code -> {
                    Map<String, String> entry = catalog.get(code);
                    return entry == null || entry.get("message") == null || entry.get("message").isBlank();
                })
                .toList();
        assertThat(missing)
                .as("codes with no en message in %s — add one in the same change set (gate D5-3)", CATALOG_RESOURCE)
                .isEmpty();
    }

    @Test
    @DisplayName("D5-4: each catalog template's {names} match the code's declared placeholders()")
    void catalogPlaceholdersMatchTheCodeContract() {
        Map<String, Set<String>> declared = new HashMap<>();
        for (CyntexErrorCode c : codes) {
            declared.put(c.code(), new TreeSet<>(c.placeholders()));
        }
        for (Map.Entry<String, Map<String, String>> entry : catalog.entrySet()) {
            String code = entry.getKey();
            assertThat(declared)
                    .as("catalog entry '%s' has no matching first-party error code (orphan template)", code)
                    .containsKey(code);
            Set<String> used = new TreeSet<>();
            used.addAll(placeholdersIn(entry.getValue().get("message")));
            used.addAll(placeholdersIn(entry.getValue().get("solution")));
            assertThat(used)
                    .as("placeholders in the catalog templates for '%s' must equal its declared "
                            + "placeholders() (gate D5-4)", code)
                    .isEqualTo(declared.get(code));
        }
    }

    private static Set<String> placeholdersIn(String template) {
        Set<String> names = new TreeSet<>();
        if (template == null) {
            return names;
        }
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }
}
