package io.cyntex.core.dsl;

import io.cyntex.core.catalog.CyntexCatalog;
import io.cyntex.core.model.Resource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Capability-matrix validation (plan poc1 C3): mode × connector legality and connector config
 * field type / enum checks, judged against the connector-derived catalog schema. The catalog is the
 * build-time projection of each connector's own spec — nothing here hard-codes connector knowledge.
 */
class CapabilityRulesTest {

    private final DslParser parser = new DslParser();
    private final CyntexCatalog catalog = CyntexCatalog.load();

    private Resource parse(String yaml) {
        return parser.parse(yaml);
    }

    @Test
    void rejectsModeOutsideConnectorCapabilityMatrix() {
        // kafka declares only [stream]; cdc is outside its matrix (ADR-0016 §4).
        Resource src = parse("""
                version: cyntex/v1
                kind: source
                id: src_k
                connector: kafka
                config: { nameSrvAddr: "k1:9092" }
                mode: cdc
                tables: [ events ]
                """);
        assertThatThrownBy(() -> CapabilityRules.validate(List.of(src), catalog))
                .isInstanceOf(DslException.class)
                .extracting(e -> ((DslException) e).code())
                .isEqualTo(DslError.UNSUPPORTED_MODE);
    }

    @Test
    void acceptsModeWithinConnectorCapabilityMatrix() {
        // mysql declares [cdc, snapshot]; snapshot is legal.
        Resource src = parse("""
                version: cyntex/v1
                kind: source
                id: src_my
                connector: mysql
                config: { host: 10.0.0.1, username: u, password: p }
                mode: snapshot
                tables: [ orders ]
                """);
        assertThatCode(() -> CapabilityRules.validate(List.of(src), catalog))
                .doesNotThrowAnyException();
    }

    @Test
    void skipsConnectorAbsentFromCatalog() {
        // oracle is an enterprise connector, not in the bundled OSS catalog. Offline cannot judge
        // its modes — connector registration is authoritative only on the server (ADR-0019 §3.3).
        Resource src = parse("""
                version: cyntex/v1
                kind: source
                id: src_ora
                connector: oracle
                config: { host: 10.0.0.2, username: u, password: p }
                mode: cdc
                tables: [ orders ]
                """);
        assertThatCode(() -> CapabilityRules.validate(List.of(src), catalog))
                .doesNotThrowAnyException();
    }

    @Test
    void skipsModeCheckWhenNonDatabaseModesAreOnlyDerived() {
        // quickapi is a SaaS connector whose real mode (api) was never declared, so the catalog
        // only carries the derived [snapshot] — an artifact, not its capability. Offline cannot
        // trust it, so mode is deferred to the server rather than wrongly rejected.
        Resource src = parse("""
                version: cyntex/v1
                kind: source
                id: src_q
                connector: quickapi
                config: { jsonTxt: "{}" }
                mode: api
                tables: [ items ]
                """);
        assertThatCode(() -> CapabilityRules.validate(List.of(src), catalog))
                .doesNotThrowAnyException();
    }

    @Test
    void skipsModeCheckWhenConnectorHasNoModes() {
        // postgres did not classload during catalog generation (no built jar), so its modes are
        // empty — no offline signal to judge against.
        Resource src = parse("""
                version: cyntex/v1
                kind: source
                id: src_pg
                connector: postgres
                config: { host: 10.0.0.5, database: erp, user: u, password: p }
                mode: cdc
                tables: [ orders ]
                """);
        assertThatCode(() -> CapabilityRules.validate(List.of(src), catalog))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsConfigValueWithWrongType() {
        // mysql.masterSlaveAddress is an array field; a scalar string is the wrong type.
        Resource src = parse("""
                version: cyntex/v1
                kind: source
                id: src_my
                connector: mysql
                config: { deploymentMode: master-slave, masterSlaveAddress: "10.0.0.1:3306", username: u, password: p }
                mode: snapshot
                tables: [ orders ]
                """);
        assertThatThrownBy(() -> CapabilityRules.validate(List.of(src), catalog))
                .isInstanceOf(DslException.class)
                .extracting(e -> ((DslException) e).code())
                .isEqualTo(DslError.CONFIG_TYPE_MISMATCH);
    }

    @Test
    void rejectsConfigValueOutsideEnum() {
        // mysql.deploymentMode is an enum [standalone, master-slave]; "cluster" is not a choice.
        Resource src = parse("""
                version: cyntex/v1
                kind: source
                id: src_my
                connector: mysql
                config: { deploymentMode: cluster, host: 10.0.0.1, username: u, password: p }
                mode: snapshot
                tables: [ orders ]
                """);
        assertThatThrownBy(() -> CapabilityRules.validate(List.of(src), catalog))
                .isInstanceOf(DslException.class)
                .extracting(e -> ((DslException) e).code())
                .isEqualTo(DslError.INVALID_CONFIG_VALUE);
    }

    @Test
    void acceptsValidArrayEnumSelection() {
        // dummy.incremental_types is a multi-select array enum [1,2,3]; a legal subset is valid.
        Resource src = parse("""
                version: cyntex/v1
                kind: source
                id: src_d
                connector: dummy
                config: { incremental_types: ["1", "2"] }
                mode: snapshot
                tables: [ t ]
                """);
        assertThatCode(() -> CapabilityRules.validate(List.of(src), catalog))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsArrayEnumElementOutsideChoices() {
        // a single out-of-range element of a multi-select array enum is rejected.
        Resource src = parse("""
                version: cyntex/v1
                kind: source
                id: src_d
                connector: dummy
                config: { incremental_types: ["1", "9"] }
                mode: snapshot
                tables: [ t ]
                """);
        assertThatThrownBy(() -> CapabilityRules.validate(List.of(src), catalog))
                .isInstanceOf(DslException.class)
                .extracting(e -> ((DslException) e).code())
                .isEqualTo(DslError.INVALID_CONFIG_VALUE);
    }

    @Test
    void rejectsIllegalModeForDatabaseConnector() {
        // mysql is a database with derived-only modes [cdc, snapshot]; stream is illegal. This
        // routes the rejection through the DATABASE trust branch (mysql carries no declared mode).
        Resource src = parse("""
                version: cyntex/v1
                kind: source
                id: src_my
                connector: mysql
                config: { host: 10.0.0.1, username: u, password: p }
                mode: stream
                tables: [ orders ]
                """);
        assertThatThrownBy(() -> CapabilityRules.validate(List.of(src), catalog))
                .isInstanceOf(DslException.class)
                .extracting(e -> ((DslException) e).code())
                .isEqualTo(DslError.UNSUPPORTED_MODE);
    }

    @Test
    void skipsInterpolatedConfigValue() {
        // ${ENV} externalization is opaque offline; an enum field set to ${...} is not enum-checked.
        Resource src = parse("""
                version: cyntex/v1
                kind: source
                id: src_my
                connector: mysql
                config: { deploymentMode: "${DEPLOY_MODE}", host: 10.0.0.1, username: u, password: p }
                mode: snapshot
                tables: [ orders ]
                """);
        assertThatCode(() -> CapabilityRules.validate(List.of(src), catalog))
                .doesNotThrowAnyException();
    }

    @Test
    void toleratesUnknownConfigKey() {
        // A key not in the connector's normalized schema is passed through (the spec may drop
        // fields); offline does not reject it.
        Resource src = parse("""
                version: cyntex/v1
                kind: source
                id: src_my
                connector: mysql
                config: { host: 10.0.0.1, username: u, password: p, notARealMysqlField: x }
                mode: snapshot
                tables: [ orders ]
                """);
        assertThatCode(() -> CapabilityRules.validate(List.of(src), catalog))
                .doesNotThrowAnyException();
    }

    @Test
    void skipsWriteTargetWithoutMode() {
        // A pure connection supplier (X18) omits mode; there is no read mode to judge.
        Resource tgt = parse("""
                version: cyntex/v1
                kind: source
                id: tgt_es
                connector: elasticsearch
                config: { host: "http://10.0.0.9:9200", username: w, password: p }
                """);
        assertThatCode(() -> CapabilityRules.validate(List.of(tgt), catalog))
                .doesNotThrowAnyException();
    }
}
