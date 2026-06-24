package io.cyntex.tools.catalog.assembler;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.cyntex.core.catalog.CatalogJson;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The auditor surfaces the two normalizer degradations the catalog cannot otherwise show: a present
 * but unrecognized Formily {@code type} token (which silently falls to a string input) and a
 * {@code ${key}} label ref that does not resolve in the default-locale bundle (which silently falls
 * back to the raw key). Hidden fields and recognized types are not flagged; containers are traversed.
 */
class SpecAuditorTest {

    @SuppressWarnings("unchecked")
    private static SpecAuditor.Findings audit(String specJson) {
        return SpecAuditor.audit((Map<String, Object>) CatalogJson.parse(specJson));
    }

    @Test
    void flagsPresentButUnrecognizedTypeTokensIncludingInsideContainers() {
        SpecAuditor.Findings findings = audit("""
                {"configOptions":{"connection":{"properties":{
                    "host":{"type":"string","title":"Host"},
                    "weird":{"type":"mystery","title":"Weird"},
                    "hiddenWeird":{"type":"ghost","x-display":"hidden","title":"x"},
                    "group":{"type":"void","properties":{
                        "nested":{"type":"alien","title":"Nested"}}}}}},
                 "messages":{"default":"en_US","en_US":{}}}
                """);

        assertThat(findings.unknownTypeFields()).containsExactly("weird", "nested");
    }

    @Test
    void flagsLabelRefsThatDoNotResolveInTheDefaultLocale() {
        SpecAuditor.Findings findings = audit("""
                {"configOptions":{"connection":{"properties":{
                    "host":{"type":"string","title":"${host_label}"},
                    "secret":{"type":"string","title":"${missing_label}"},
                    "literal":{"type":"string","title":"Plain"}}}},
                 "messages":{"default":"en_US","en_US":{"host_label":"Host"}}}
                """);

        assertThat(findings.unresolvedLabelRefs()).containsExactly("missing_label");
    }

    @Test
    void aCleanSpecHasNoFindings() {
        SpecAuditor.Findings findings = audit("""
                {"configOptions":{"connection":{"properties":{
                    "host":{"type":"string","title":"${host_label}"}}}},
                 "messages":{"default":"en_US","en_US":{"host_label":"Host"}}}
                """);

        assertThat(findings.unknownTypeFields()).isEmpty();
        assertThat(findings.unresolvedLabelRefs()).isEmpty();
    }
}
