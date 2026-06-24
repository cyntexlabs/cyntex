package io.cyntex.cli;

import io.cyntex.core.catalog.ConfigField;
import io.cyntex.core.catalog.ConfigType;
import io.cyntex.core.catalog.EnumOption;
import io.cyntex.core.catalog.VisibleWhen;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The connector config Q&A, driven by synthetic field descriptors (decoupled from the live catalog
 * so the tests stay stable as connector specs evolve). Covers the value rules the wizard must honour:
 * type-aware coercion, enum choices, masked secrets, conditional visibility, and blank = omit.
 */
class ConfigPrompterTest {

    private static ConfigField field(String name, ConfigType type, boolean secret,
                                     List<EnumOption> options, VisibleWhen visibleWhen) {
        return new ConfigField(name, type, Map.of("en_US", name), false, null, secret, options, visibleWhen);
    }

    private static EnumOption opt(String value) {
        return new EnumOption(value, Map.of("en_US", value));
    }

    @Test
    void asksStringFieldsIncludingAnsweredOmittingBlank() {
        List<ConfigField> fields = List.of(
                field("host", ConfigType.STRING, false, List.of(), null),
                field("schema", ConfigType.STRING, false, List.of(), null));
        ScriptedPrompter p = new ScriptedPrompter("10.0.0.1", "");

        Map<String, Object> cfg = new ConfigPrompter().collect(fields, p);

        assertThat(cfg).hasSize(1).containsEntry("host", "10.0.0.1");
    }

    @Test
    void enumFieldOffersValuesPlusSkipAndRecordsTheChoice() {
        List<ConfigField> fields = List.of(
                field("deploymentMode", ConfigType.STRING, false, List.of(opt("standalone"), opt("master-slave")), null));
        ScriptedPrompter p = new ScriptedPrompter("standalone");

        Map<String, Object> cfg = new ConfigPrompter().collect(fields, p);

        assertThat(cfg).containsEntry("deploymentMode", "standalone");
        assertThat(p.offered.get(0)).containsExactly("standalone", "master-slave", "(skip)");
    }

    @Test
    void enumFieldIsOmittedWhenSkipChosen() {
        List<ConfigField> fields = List.of(
                field("deploymentMode", ConfigType.STRING, false, List.of(opt("standalone")), null));
        ScriptedPrompter p = new ScriptedPrompter("(skip)");

        assertThat(new ConfigPrompter().collect(fields, p)).isEmpty();
    }

    @Test
    void conditionalFieldIsAskedOnlyWhenItsControllerMatches() {
        ConfigField deploy = field("deploymentMode", ConfigType.STRING, false,
                List.of(opt("standalone"), opt("uri")), null);
        ConfigField gated = field("authType", ConfigType.STRING, false, List.of(opt("password")),
                new VisibleWhen("deploymentMode", List.of("standalone")));
        List<ConfigField> fields = List.of(deploy, gated);

        // controller answered "uri" -> the gated enum is never offered
        ScriptedPrompter hidden = new ScriptedPrompter("uri");
        assertThat(new ConfigPrompter().collect(fields, hidden)).doesNotContainKey("authType");
        assertThat(hidden.offered).hasSize(1);

        // controller answered "standalone" -> the gated enum is offered
        ScriptedPrompter shown = new ScriptedPrompter("standalone", "password");
        Map<String, Object> cfg = new ConfigPrompter().collect(fields, shown);
        assertThat(cfg).containsEntry("authType", "password");
        assertThat(shown.offered).hasSize(2);
    }

    @Test
    void coercesNumberAndBooleanAnswersToTypedValues() {
        List<ConfigField> fields = List.of(
                field("port", ConfigType.NUMBER, false, List.of(), null),
                field("ssl", ConfigType.BOOLEAN, false, List.of(), null));
        ScriptedPrompter p = new ScriptedPrompter("1521", "true");

        Map<String, Object> cfg = new ConfigPrompter().collect(fields, p);

        assertThat(cfg).containsEntry("port", 1521).containsEntry("ssl", true);
    }

    @Test
    void secretFieldsUseTheMaskedPrompt() {
        List<ConfigField> fields = List.of(field("password", ConfigType.STRING, true, List.of(), null));
        ScriptedPrompter p = new ScriptedPrompter("s3cr3t");

        Map<String, Object> cfg = new ConfigPrompter().collect(fields, p);

        assertThat(cfg).containsEntry("password", "s3cr3t");
        assertThat(p.secretQuestions).hasSize(1);
    }
}
